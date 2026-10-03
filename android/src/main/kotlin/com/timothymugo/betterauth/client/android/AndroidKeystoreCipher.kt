package com.timothymugo.betterauth.client.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [ValueCipher] whose AES-256 key is generated inside, and never leaves, the Android Keystore (hardware-backed where
 * the device has it). The key is created on first use under [alias].
 *
 * The key does not survive an app reinstall, a device restore or a factory reset, while a restored data file might.
 * [EncryptedDataStoreStorage] treats such unreadable data as "signed out". Exclude the storage file from Auto Backup
 * (see the README) to avoid restoring it at all.
 */
public class AndroidKeystoreCipher(private val alias: String = DEFAULT_ALIAS) : AesGcmValueCipher({ loadOrCreateKey(alias) }) {

    /** Deletes the key; everything encrypted with it becomes unreadable. For tests and explicit wipes. */
    public fun deleteKey() {
        synchronized(LOCK) {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }
    }

    public companion object {
        public const val DEFAULT_ALIAS: String = "better_auth_kt_client_session_key"
        private const val PROVIDER = "AndroidKeyStore"
        private val LOCK = Any()

        private fun loadOrCreateKey(alias: String): SecretKey = synchronized(LOCK) {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generator.generateKey()
        }
    }
}
