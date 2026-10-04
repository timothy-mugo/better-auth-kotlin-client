package com.timothymugo.betterauth.client.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.preferencesDataStoreFile
import com.timothymugo.betterauth.client.BetterAuthClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** `androidClient(context)` with its real defaults: encrypted DataStore + an Android Keystore key. */
@RunWith(AndroidJUnit4::class)
class AndroidClientInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "ba_test_${UUID.randomUUID()}"
    private val fileName = "ba_test_${UUID.randomUUID()}"
    private val requests = mutableListOf<HttpRequestData>()
    private val engine = MockEngine { request ->
        requests += request
        respond("""{"ok":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @After
    fun tearDown() {
        runCatching { AndroidKeystoreCipher(alias).deleteKey() }
        runCatching { context.preferencesDataStoreFile(fileName).delete() }
    }

    @Test
    fun theSessionIsPersistedEncryptedByDefault() = runBlocking {
        val auth = BetterAuthClient {
            baseUrl = "https://auth.example.com/api/auth"
            engine = this@AndroidClientInstrumentedTest.engine
            plugins(androidClient(context) { storageFileName = fileName; keyAlias = alias; scheme = "myapp" })
        }
        auth.restoreToken("super-secret-session-token")

        // same instance the plugin created: it holds what the client wrote
        val storage = EncryptedDataStoreStorage.create(context, fileName, alias)
        assertNotNull(storage.getItem("better-auth_cookie"))
        val onDisk = context.preferencesDataStoreFile(fileName).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("the token must not be readable on disk", onDisk.contains("super-secret-session-token"))
        assertEquals("super-secret-session-token", auth.currentToken())
    }

    @Test
    fun theHooksRunOnADevice() = runBlocking {
        val auth = BetterAuthClient {
            baseUrl = "https://auth.example.com/api/auth"
            engine = this@AndroidClientInstrumentedTest.engine
            plugins(androidClient(context) { storageFileName = fileName; keyAlias = alias; scheme = "myapp" })
        }
        auth.ok()
        assertEquals("myapp://", requests.last().headers["expo-origin"])
        assertEquals("myapp://dashboard", auth.android.deepLink("/dashboard"))
    }
}
