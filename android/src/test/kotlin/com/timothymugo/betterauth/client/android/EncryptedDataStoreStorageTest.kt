package com.timothymugo.betterauth.client.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.session.StorageSessionStore
import com.timothymugo.betterauth.client.session.StoredCookie
import com.timothymugo.betterauth.client.session.StoredSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The storage logic on the JVM. The real Keystore key is covered by the instrumented tests in `androidTest`. */
class EncryptedDataStoreStorageTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    private fun cipher(seed: Int = 1) = AesGcmValueCipher { SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES") }

    @Before
    fun setUp() {
        dir = File.createTempFile("ba-ds", "").also { it.delete(); it.mkdirs() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "store.preferences_pb") }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun rawValue(key: String): String? = runBlocking { dataStore.data.first()[stringPreferencesKey(key)] }

    @Test
    fun `set get remove`() = runBlocking {
        val storage = EncryptedDataStoreStorage(dataStore, cipher())
        assertNull(storage.getItem("k"))
        storage.setItem("k", "v1")
        assertEquals("v1", storage.getItem("k"))
        storage.setItem("k", "v2")
        assertEquals("v2", storage.getItem("k"))
        storage.removeItem("k")
        assertNull(storage.getItem("k"))
        assertNull(rawValue("k"))
    }

    @Test
    fun `values are encrypted on disk`() = runBlocking {
        val storage = EncryptedDataStoreStorage(dataStore, cipher())
        storage.setItem("better-auth_cookie", "super-secret-session-token")
        val raw = assertNotNull(rawValue("better-auth_cookie"))
        assertFalse(raw.contains("super-secret-session-token"))
        val file = File(dir, "store.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(file.contains("super-secret-session-token"))
    }

    @Test
    fun `a fresh storage over the same data reads it back (process restart)`() = runBlocking {
        EncryptedDataStoreStorage(dataStore, cipher()).setItem("k", "persisted")
        // new instance, empty in-memory memo, same file and key
        assertEquals("persisted", EncryptedDataStoreStorage(dataStore, cipher()).getItem("k"))
    }

    @Test
    fun `data written under another key reads as missing and is deleted`() = runBlocking {
        EncryptedDataStoreStorage(dataStore, cipher(seed = 1)).setItem("k", "secret")
        val other = EncryptedDataStoreStorage(dataStore, cipher(seed = 99))
        assertNull(other.getItem("k"))
        assertNull(rawValue("k"))
    }

    @Test
    fun `tampered or garbage entries read as missing instead of throwing`() = runBlocking {
        val storage = EncryptedDataStoreStorage(dataStore, cipher())
        storage.setItem("k", "secret")
        val raw = rawValue("k")!!
        dataStoreEdit("k", raw.dropLast(6) + "AAAAAA")
        assertNull(EncryptedDataStoreStorage(dataStore, cipher()).getItem("k"))

        dataStoreEdit("g", "!!! not base64 !!!")
        assertNull(EncryptedDataStoreStorage(dataStore, cipher()).getItem("g"))
        assertNull(rawValue("g"))
    }

    private suspend fun dataStoreEdit(key: String, value: String) {
        dataStore.updateData { prefs -> prefs.toMutablePreferences().also { it[stringPreferencesKey(key)] = value } }
    }

    @Test
    fun `cipher rejects truncated ciphertext and uses a fresh iv`() {
        val c = cipher()
        val a = c.encrypt("x".toByteArray())
        val b = c.encrypt("x".toByteArray())
        assertFalse(a.contentEquals(b))
        assertFailsWith<java.security.GeneralSecurityException> { c.decrypt(ByteArray(5)) }
    }

    @Test
    fun `the SDK session survives a restart through encrypted storage`() = runBlocking {
        val server = MockEngine {
            respond(
                content = """{"ok":true}""",
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "set-auth-token" to listOf("tok.sig"),
                    "Set-Cookie" to listOf("better-auth.session_token=tok.sig; Max-Age=3600"),
                ),
            )
        }
        val first = BetterAuthClient {
            baseUrl = "https://auth.example.com/api/auth"
            engine = server
            storage = EncryptedDataStoreStorage(dataStore, cipher())
        }
        first.ok()

        val relaunched = BetterAuthClient {
            baseUrl = "https://auth.example.com/api/auth"
            engine = server
            storage = EncryptedDataStoreStorage(dataStore, cipher())
        }
        assertEquals("tok.sig", relaunched.currentToken())
        assertNotNull(rawValue("better-auth_cookie"))
        assertFalse(rawValue("better-auth_cookie")!!.contains("tok.sig"))
    }

    @Test
    fun `StorageSessionStore works on top of it`() = runBlocking {
        val store = StorageSessionStore(EncryptedDataStoreStorage(dataStore, cipher()))
        val session = StoredSession(token = "t", cookies = mapOf("c" to StoredCookie("c", "v")))
        store.set(session)
        assertEquals(session, store.get())
        store.clear()
        assertTrue(store.get().isEmpty)
    }
}
