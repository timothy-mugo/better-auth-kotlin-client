package com.timothymugo.betterauth.client.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timothymugo.betterauth.client.session.StorageSessionStore
import com.timothymugo.betterauth.client.session.StoredCookie
import com.timothymugo.betterauth.client.session.StoredSession
import java.security.GeneralSecurityException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator: exercises the real Android Keystore and a real DataStore file. */
@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val aliases = mutableListOf<String>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val files = mutableListOf<String>()

    private fun alias() = "ba_test_${UUID.randomUUID()}".also { aliases += it }

    private fun fileName() = "ba_test_${UUID.randomUUID()}".also { files += it }

    private fun dataStore(name: String): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        aliases.forEach { runCatching { AndroidKeystoreCipher(it).deleteKey() } }
        files.forEach { runCatching { context.preferencesDataStoreFile(it).delete() } }
    }

    @Test
    fun cipherRoundTripsThroughTheKeystore() {
        val cipher = AndroidKeystoreCipher(alias())
        val plain = "session-token-ü-🌍".toByteArray()
        val encrypted = cipher.encrypt(plain)
        assertFalse(encrypted.contentEquals(plain))
        assertArrayEquals(plain, cipher.decrypt(encrypted))
        assertFalse("fresh IV per message", cipher.encrypt(plain).contentEquals(encrypted))
    }

    @Test
    fun theKeyPersistsUnderItsAlias() {
        val alias = alias()
        val encrypted = AndroidKeystoreCipher(alias).encrypt("x".toByteArray())
        assertArrayEquals("x".toByteArray(), AndroidKeystoreCipher(alias).decrypt(encrypted))
    }

    @Test
    fun anotherAliasCannotDecrypt() {
        val encrypted = AndroidKeystoreCipher(alias()).encrypt("x".toByteArray())
        try {
            AndroidKeystoreCipher(alias()).decrypt(encrypted)
            fail("decrypting with a different key must fail")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun storageEncryptsOnDiskAndReadsBack() = runBlocking {
        val name = fileName()
        val storage = EncryptedDataStoreStorage.create(context, name, alias())
        storage.setItem("better-auth_cookie", "super-secret-session-token")
        assertEquals("super-secret-session-token", storage.getItem("better-auth_cookie"))

        val onDisk = context.preferencesDataStoreFile(name).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("super-secret-session-token"))
        assertTrue(onDisk.contains("better-auth_cookie"))

        storage.removeItem("better-auth_cookie")
        assertNull(storage.getItem("better-auth_cookie"))
    }

    @Test
    fun createReturnsOneInstancePerFile() {
        val name = fileName()
        val alias = alias()
        assertSame(
            EncryptedDataStoreStorage.create(context, name, alias),
            EncryptedDataStoreStorage.create(context, name, alias),
        )
    }

    @Test
    fun lostKeyReadsAsMissingAndDeletesTheEntry() = runBlocking {
        val name = fileName()
        val alias = alias()
        val ds = dataStore(name)
        EncryptedDataStoreStorage(ds, AndroidKeystoreCipher(alias)).setItem("k", "secret")
        assertNotNull(ds.data.first()[stringPreferencesKey("k")])

        // what happens after a reinstall/restore: the data file is there, the key is gone
        AndroidKeystoreCipher(alias).deleteKey()

        val restored = EncryptedDataStoreStorage(ds, AndroidKeystoreCipher(alias))
        assertNull(restored.getItem("k"))
        assertNull(ds.data.first()[stringPreferencesKey("k")])

        // and the storage is usable again with a freshly generated key
        restored.setItem("k", "again")
        assertEquals("again", restored.getItem("k"))
    }

    @Test
    fun sdkSessionStoreWorksOnTopOfKeystoreStorage() = runBlocking {
        val store = StorageSessionStore(EncryptedDataStoreStorage.create(context, fileName(), alias()), "myapp")
        val session = StoredSession(token = "t.sig", cookies = mapOf("c" to StoredCookie("c", "v%3D", 42L)))
        store.set(session)
        assertEquals(session, store.get())
        store.setSessionCache("""{"user":{"id":"u1"}}""")
        assertEquals("""{"user":{"id":"u1"}}""", store.getSessionCache())
        store.clear()
        assertTrue(store.get().isEmpty)
        assertNull(store.getSessionCache())
    }
}
