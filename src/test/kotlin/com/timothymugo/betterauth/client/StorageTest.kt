package com.timothymugo.betterauth.client

import com.timothymugo.betterauth.client.session.StorageSessionStore
import com.timothymugo.betterauth.client.session.StoredCookie
import com.timothymugo.betterauth.client.session.StoredSession
import com.timothymugo.betterauth.client.storage.InMemoryStorage
import com.timothymugo.betterauth.client.storage.KeyValueStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
    private val future = "2999-01-01T00:00:00.000Z"
    private val sessionBody = """{"session":${SESSION_JSON.replace("2026-02-01T00:00:00.000Z", future)},"user":$USER_JSON}"""

    // --- StorageSessionStore -------------------------------------------------------------------------------------

    @Test
    fun `store round trips and uses the expo style keys`() = runTest {
        val storage = InMemoryStorage()
        val store = StorageSessionStore(storage, "myapp")
        val session = StoredSession(token = "t", jwt = "j", cookies = mapOf("c" to StoredCookie("c", "v", 123L)))
        store.set(session)

        assertEquals(session, store.get())
        assertNotNull(storage.getItem("myapp_cookie"))
        assertNull(storage.getItem("better-auth_cookie"))
    }

    @Test
    fun `empty session removes the key, clear removes the cache too`() = runTest {
        val storage = InMemoryStorage()
        val store = StorageSessionStore(storage, "p")
        store.set(StoredSession(token = "t"))
        store.setSessionCache("{}")
        store.set(StoredSession())
        assertNull(storage.getItem("p_cookie"))
        assertEquals("{}", store.getSessionCache())
        store.clear()
        assertNull(storage.getItem("p_session_data"))
    }

    @Test
    fun `corrupt stored data reads as no session`() = runTest {
        val storage = InMemoryStorage()
        storage.setItem("better-auth_cookie", "not json {{{")
        assertTrue(StorageSessionStore(storage).get().isEmpty)
    }

    // --- persistence through the client -------------------------------------------------------------------------

    @Test
    fun `a new client with the same storage resumes the session`() = runTest {
        val storage = InMemoryStorage()
        val server = TestServer { req ->
            if (req.path.endsWith("/sign-in/email")) json(
                """{"redirect":false,"token":"t","user":$USER_JSON}""",
                headers = arrayOf("set-auth-token" to listOf("t.sig"), "Set-Cookie" to listOf("better-auth.dont_remember=1; Max-Age=3600")),
            ) else json(sessionBody)
        }
        server.client { this.storage = storage }.signIn.email("a@b.co", "pw")

        val relaunched = server.client { this.storage = storage }
        assertEquals("t.sig", relaunched.currentToken())
        relaunched.getSession()
        assertEquals("Bearer t.sig", server.last.header("Authorization"))
        assertEquals("better-auth.dont_remember=1", server.last.header("Cookie"))
    }

    @Test
    fun `storagePrefix separates sessions`() = runTest {
        val storage = InMemoryStorage()
        val server = TestServer { json("{\"ok\":true}") }
        server.client { this.storage = storage; storagePrefix = "a" }.restoreToken("token-a")
        server.client { this.storage = storage; storagePrefix = "b" }.restoreToken("token-b")
        assertEquals("token-a", server.client { this.storage = storage; storagePrefix = "a" }.currentToken())
        assertEquals("token-b", server.client { this.storage = storage; storagePrefix = "b" }.currentToken())
    }

    @Test
    fun `withStorage gives each user an isolated session on shared storage`() = runTest {
        val storage = InMemoryStorage()
        val server = TestServer { req ->
            if (req.path.endsWith("/get-session"))
                json(sessionBody, headers = arrayOf("set-auth-token" to listOf("rotated-${req.header("Authorization")?.removePrefix("Bearer ")}")))
            else json("{\"ok\":true}")
        }
        val parent = server.client()
        val alice = parent.withStorage(storage, prefix = "user-alice").also { it.restoreToken("alice") }
        val bob = parent.withStorage(storage, prefix = "user-bob").also { it.restoreToken("bob") }

        alice.getSession()
        assertEquals("Bearer alice", server.last.header("Authorization"))
        bob.getSession()
        assertEquals("Bearer bob", server.last.header("Authorization"))

        // the rotated tokens were persisted per prefix; a fresh view over the same storage sees them
        assertEquals("rotated-alice", parent.withStorage(storage, "user-alice").currentToken())
        assertEquals("rotated-bob", parent.withStorage(storage, "user-bob").currentToken())
        assertNull(parent.currentToken())
    }

    // --- Better Auth cookies only -------------------------------------------------------------------------------

    @Test
    fun `cookies that are not Better Auth's are ignored`() = runTest {
        val server = TestServer {
            json(
                "{\"ok\":true}",
                headers = arrayOf(
                    "Set-Cookie" to listOf(
                        "__cf_bm=cdn; Max-Age=1800",
                        "AWSALB=lb; Max-Age=3600",
                        "__Secure-qareplus.two_factor=x; Max-Age=600",
                    ),
                ),
            )
        }
        val auth = server.client { cookiePrefix = "qareplus" }
        auth.ok()
        assertEquals(setOf("__Secure-qareplus.two_factor"), auth.storedSession().cookies.keys)
        assertEquals("__Secure-qareplus.two_factor=x", auth.cookieHeader())
    }

    // --- cached session -----------------------------------------------------------------------------------------

    @Test
    fun `getSession caches the result and cachedSession reads it back without the network`() = runTest {
        val storage = InMemoryStorage()
        val server = TestServer { json(sessionBody) }
        server.client { this.storage = storage }.getSession()
        val requests = server.requests.size

        val relaunched = server.client { this.storage = storage }
        val cached = relaunched.cachedSession()
        assertNotNull(cached)
        assertEquals("u1", cached.user.id)
        assertEquals("ke", (cached.user.additionalFields["countryId"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("org1", cached.session.activeOrganizationId)
        assertEquals(requests, server.requests.size)
        assertNotNull(storage.getItem("better-auth_session_data"))
    }

    @Test
    fun `an expired cached session is not returned`() = runTest {
        val server = TestServer { json("""{"session":$SESSION_JSON,"user":$USER_JSON}""") } // expires 2026-02-01
        val auth = server.client()
        auth.getSession()
        assertNull(auth.cachedSession())
    }

    @Test
    fun `a null session clears the cache and signOut clears it`() = runTest {
        var body = sessionBody
        val server = TestServer { req -> if (req.path.endsWith("/sign-out")) json("""{"success":true}""") else json(body) }
        val auth = server.client()
        auth.getSession()
        assertNotNull(auth.cachedSession())

        body = "null"
        auth.getSession()
        assertNull(auth.cachedSession())

        body = sessionBody
        auth.getSession()
        assertNotNull(auth.cachedSession())
        auth.signOut()
        assertNull(auth.cachedSession())
    }

    @Test
    fun `disableCache turns the session cache off`() = runTest {
        val storage = InMemoryStorage()
        val server = TestServer { json(sessionBody) }
        val auth = server.client { this.storage = storage; disableCache = true }
        auth.getSession()
        assertNull(auth.cachedSession())
        assertNull(storage.getItem("better-auth_session_data"))
    }
}

/** Storage that is slow to read, to widen the window in which two unsynchronized writers would lose an update. */
private class SlowStorage(private val inner: InMemoryStorage = InMemoryStorage()) : KeyValueStorage {
    override suspend fun getItem(key: String): String? {
        delay(40)
        return inner.getItem(key)
    }

    override suspend fun setItem(key: String, value: String) = inner.setItem(key, value)

    override suspend fun removeItem(key: String) = inner.removeItem(key)
}

class StorageConcurrencyTest {
    @Test
    fun `views over the same storage and prefix do not lose each other's cookies`() = runBlocking {
        val storage = SlowStorage()
        val server = TestServer { req ->
            val name = req.url.parameters["c"]!!
            json("{\"ok\":true}", headers = arrayOf("Set-Cookie" to listOf("better-auth.$name=1; Max-Age=3600")))
        }
        val parent = server.client()
        // two views of the same user, as a per-request backend pattern creates them
        val a = parent.withStorage(storage, "user-1")
        val b = parent.withStorage(storage, "user-1")

        val callA = async(Dispatchers.Default) { a.request("GET", "/x", query = mapOf("c" to "first")) }
        val callB = async(Dispatchers.Default) { b.request("GET", "/x", query = mapOf("c" to "second")) }
        callA.await().getOrThrow()
        callB.await().getOrThrow()

        val persisted = StorageSessionStore(storage, "user-1").get().cookies.keys
        assertEquals(setOf("better-auth.first", "better-auth.second"), persisted)
    }

    @Test
    fun `different users do not share a session`() = runBlocking {
        val storage = SlowStorage()
        val server = TestServer { req ->
            json("{\"ok\":true}", headers = arrayOf("Set-Cookie" to listOf("better-auth.${req.url.parameters["c"]}=1; Max-Age=3600")))
        }
        val parent = server.client()
        val alice = async(Dispatchers.Default) { parent.withStorage(storage, "alice").request("GET", "/x", query = mapOf("c" to "a")) }
        val bob = async(Dispatchers.Default) { parent.withStorage(storage, "bob").request("GET", "/x", query = mapOf("c" to "b")) }
        alice.await().getOrThrow()
        bob.await().getOrThrow()
        assertEquals(setOf("better-auth.a"), StorageSessionStore(storage, "alice").get().cookies.keys)
        assertEquals(setOf("better-auth.b"), StorageSessionStore(storage, "bob").get().cookies.keys)
    }
}
