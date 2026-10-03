package com.timothymugo.betterauth.client.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.timothymugo.betterauth.client.storage.KeyValueStorage
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * [KeyValueStorage] backed by Jetpack DataStore, with every value encrypted by a [ValueCipher] (by default an AES-GCM
 * key in the Android Keystore). Pass it to the client like the Expo plugin's `storage` option:
 *
 * ```kotlin
 * val auth = BetterAuthClient {
 *     baseUrl = "https://api.example.com/api/auth"
 *     storage = EncryptedDataStoreStorage.create(applicationContext)
 * }
 * ```
 *
 * Data that cannot be decrypted (key lost on restore or reinstall, tampering, corruption) reads as missing and is
 * deleted, which the SDK sees as "signed out". It never throws on bad data.
 *
 * Only one instance may use a given DataStore file per process; [create] guarantees that by caching per file name.
 */
public class EncryptedDataStoreStorage(
    private val dataStore: DataStore<Preferences>,
    private val cipher: ValueCipher,
) : KeyValueStorage {
    // Keystore operations cost milliseconds; keep decrypted values for the life of the process. Writes go through here,
    // and only this process owns the file, so the memo cannot go stale.
    private val plaintext = ConcurrentHashMap<String, String>()

    override suspend fun getItem(key: String): String? {
        plaintext[key]?.let { return it }
        val encoded = dataStore.data.first()[stringPreferencesKey(key)] ?: return null
        val value = runCatching { String(cipher.decrypt(Base64.decode(encoded)), Charsets.UTF_8) }.getOrNull()
        if (value == null) {
            removeItem(key)
            return null
        }
        plaintext[key] = value
        return value
    }

    override suspend fun setItem(key: String, value: String) {
        val encoded = Base64.encode(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[stringPreferencesKey(key)] = encoded }
        plaintext[key] = value
    }

    override suspend fun removeItem(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
        plaintext.remove(key)
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String = "better_auth_kt_client"

        private val stores = HashMap<String, EncryptedDataStoreStorage>()

        /**
         * Storage in `<files dir>/datastore/<fileName>.preferences_pb`, encrypted with an Android Keystore key.
         * Calling it again with the same [fileName] returns the same instance.
         *
         * Give each distinct [keyAlias] its own [fileName]; data written under one key can't be read with another.
         */
        public fun create(
            context: Context,
            fileName: String = DEFAULT_FILE_NAME,
            keyAlias: String = AndroidKeystoreCipher.DEFAULT_ALIAS,
        ): EncryptedDataStoreStorage {
            val appContext = context.applicationContext
            // Not computeIfAbsent: ConcurrentHashMap's version needs API 24 and this library supports 23.
            return synchronized(stores) {
                stores.getOrPut(fileName) {
                val dataStore = PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    produceFile = { appContext.preferencesDataStoreFile(fileName) },
                )
                EncryptedDataStoreStorage(dataStore, AndroidKeystoreCipher(keyAlias))
                }
            }
        }
    }
}
