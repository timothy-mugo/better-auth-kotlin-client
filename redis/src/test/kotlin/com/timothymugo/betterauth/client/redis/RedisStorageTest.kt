package com.timothymugo.betterauth.client.redis

import com.timothymugo.betterauth.client.BetterAuthClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against every server named by `REDIS_URL` (default `redis://localhost:6379`) and `VALKEY_URL` (optional).
 * A server that is not reachable is skipped, so the suite stays green without Redis installed.
 */
class RedisStorageTest {
    private val servers: List<Pair<String, String>> = listOfNotNull(
        "redis" to (System.getenv("REDIS_URL") ?: "redis://localhost:6379"),
        System.getenv("VALKEY_URL")?.let { "valkey" to it },
    )

    private fun reachable(uri: String): Boolean = runCatching {
        val client = RedisClient.create(RedisURI.create(uri))
        try {
            client.connect().use { it.sync().ping() }
            true
        } finally {
            client.shutdown()
        }
    }.getOrDefault(false)

    private fun eachServer(block: suspend (name: String, uri: String, prefix: String) -> Unit) {
        val live = servers.filter { reachable(it.second) }
        if (live.isEmpty()) {
            println("RedisStorageTest: no Redis/Valkey reachable (${servers.joinToString { it.second }}), skipping")
            return
        }
        for ((name, uri) in live) runBlocking { block(name, uri, "test:${UUID.randomUUID()}:") }
    }

    @Test
    fun `set get remove`() = eachServer { _, uri, prefix ->
        RedisStorage.connect(uri, keyPrefix = prefix).use { storage ->
            assertNull(storage.getItem("k"))
            storage.setItem("k", "v1")
            assertEquals("v1", storage.getItem("k"))
            storage.setItem("k", "v2")
            assertEquals("v2", storage.getItem("k"))
            storage.removeItem("k")
            assertNull(storage.getItem("k"))
        }
    }

    @Test
    fun `values with unicode and json round trip`() = eachServer { _, uri, prefix ->
        RedisStorage.connect(uri, keyPrefix = prefix).use { storage ->
            val value = """{"name":"Zuri Muñoz 🌍","cookies":{"a":{"value":"x%3D"}}}"""
            storage.setItem("better-auth_cookie", value)
            assertEquals(value, storage.getItem("better-auth_cookie"))
            storage.removeItem("better-auth_cookie")
        }
    }

    @Test
    fun `keys are namespaced by the prefix`() = eachServer { _, uri, prefix ->
        RedisStorage.connect(uri, keyPrefix = prefix + "a:").use { a ->
            RedisStorage.connect(uri, keyPrefix = prefix + "b:").use { b ->
                a.setItem("k", "from-a")
                assertNull(b.getItem("k"))
                b.setItem("k", "from-b")
                assertEquals("from-a", a.getItem("k"))
                a.removeItem("k")
                b.removeItem("k")
            }
        }
    }

    @Test
    fun `ttl is applied on write and can be disabled`() = eachServer { _, uri, prefix ->
        val client = RedisClient.create(RedisURI.create(uri))
        client.connect().use { raw ->
            val sync = raw.sync()
            RedisStorage.connect(uri, keyPrefix = prefix, ttl = Duration.ofSeconds(120)).use {
                it.setItem("a", "v")
                assertTrue(sync.ttl(prefix + "a") in 1..120, "ttl was ${sync.ttl(prefix + "a")}")
                it.removeItem("a")
            }
            RedisStorage.connect(uri, keyPrefix = prefix, ttl = null).use {
                it.setItem("b", "v")
                assertEquals(-1L, sync.ttl(prefix + "b"))
                it.removeItem("b")
            }
        }
        client.shutdown()
    }

    @Test
    fun `values are encrypted at rest and unreadable with another key`() = eachServer { _, uri, prefix ->
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val client = RedisClient.create(RedisURI.create(uri))
        client.connect().use { raw ->
            RedisStorage.connect(uri, keyPrefix = prefix, cipher = AesGcmStringCipher(keyA)).use { enc ->
                enc.setItem("k", "secret-session-token")
                val atRest = raw.sync().get(prefix + "k")
                assertFalse(atRest.contains("secret-session-token"))
                assertTrue(atRest.startsWith("v1:"))
                assertEquals("secret-session-token", enc.getItem("k"))
            }
            RedisStorage.connect(uri, keyPrefix = prefix, cipher = AesGcmStringCipher(keyB)).use { wrongKey ->
                assertNull(wrongKey.getItem("k"))
            }
            RedisStorage.connect(uri, keyPrefix = prefix).use { plain ->
                // plain storage sees ciphertext, never the token
                assertNotEquals("secret-session-token", plain.getItem("k"))
                plain.removeItem("k")
            }
        }
        client.shutdown()
    }

    @Test
    fun `an unencrypted legacy value is treated as missing when a cipher is configured`() = eachServer { _, uri, prefix ->
        RedisStorage.connect(uri, keyPrefix = prefix).use { plain -> plain.setItem("k", "plaintext") }
        RedisStorage.connect(uri, keyPrefix = prefix, cipher = AesGcmStringCipher(ByteArray(32))).use { enc ->
            assertNull(enc.getItem("k"))
            enc.removeItem("k")
        }
    }

    @Test
    fun `a client persists one session per user in redis`() = eachServer { _, uri, prefix ->
        val engine = MockEngine { request ->
            val who = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") ?: "new"
            respond(
                content = """{"ok":true}""",
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "set-auth-token" to listOf("rotated-$who"),
                ),
            )
        }
        val auth = BetterAuthClient {
            baseUrl = "https://auth.example.com/api/auth"
            this.engine = engine
        }
        RedisStorage.connect(uri, keyPrefix = prefix).use { storage ->
            val alice = auth.withStorage(storage, "user-alice").also { it.restoreToken("alice") }
            val bob = auth.withStorage(storage, "user-bob").also { it.restoreToken("bob") }
            alice.ok()
            bob.ok()

            // a different process (new client, same Redis) resumes each user's rotated session
            assertEquals("rotated-alice", auth.withStorage(storage, "user-alice").currentToken())
            assertEquals("rotated-bob", auth.withStorage(storage, "user-bob").currentToken())

            auth.withStorage(storage, "user-alice").clearLocalSession()
            assertNull(auth.withStorage(storage, "user-alice").currentToken())
            assertEquals("rotated-bob", auth.withStorage(storage, "user-bob").currentToken())
            auth.withStorage(storage, "user-bob").clearLocalSession()
        }
        auth.close()
    }
}

class AesGcmStringCipherTest {
    private val cipher = AesGcmStringCipher(ByteArray(32) { it.toByte() })

    @Test
    fun `round trips and is randomized`() {
        val a = cipher.encrypt("hello")
        val b = cipher.encrypt("hello")
        assertNotEquals(a, b)
        assertEquals("hello", cipher.decrypt(a))
        assertEquals("hello", cipher.decrypt(b))
    }

    @Test
    fun `tampering is detected`() {
        val encoded = cipher.encrypt("hello")
        val tampered = encoded.dropLast(4) + (if (encoded.takeLast(4) == "AAAA") "BBBB" else "AAAA")
        kotlin.test.assertFails { cipher.decrypt(tampered) }
        kotlin.test.assertFails { cipher.decrypt("not-ours") }
    }

    @Test
    fun `rejects bad key sizes`() {
        kotlin.test.assertFails { AesGcmStringCipher(ByteArray(10)) }
    }
}
