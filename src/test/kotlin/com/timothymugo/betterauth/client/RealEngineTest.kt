package com.timothymugo.betterauth.client

import com.sun.net.httpserver.HttpServer
import com.timothymugo.betterauth.client.model.SignInOutcome
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the default OkHttp engine against a local JDK HTTP server, which MockEngine cannot do: separate `Set-Cookie`
 * headers, header casing on the wire, the base path, and redirects that must not be followed.
 */
class RealEngineTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String)

    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Seen>()
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}/api/auth"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth") { ex ->
            seen += Seen(
                ex.requestMethod,
                ex.requestURI.toString(),
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value },
                ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            val path = ex.requestURI.path
            when {
                path.endsWith("/sign-in/email") -> {
                    ex.responseHeaders.add("Set-Cookie", "better-auth.session_token=tok.sig%3D; Max-Age=3600; Path=/; HttpOnly")
                    ex.responseHeaders.add("Set-Cookie", "better-auth.last_used_login_method=email; Max-Age=3600; Path=/")
                    ex.responseHeaders.add("set-auth-token", "tok.sig=")
                    reply(ex, 200, """{"redirect":false,"token":"tok","user":{"id":"u1","name":"Ada","email":"a@b.co","emailVerified":true}}""")
                }
                path.endsWith("/verify-email") -> {
                    ex.responseHeaders.add("Location", "myapp://verified")
                    ex.sendResponseHeaders(302, -1)
                    ex.close()
                }
                path.endsWith("/boom") -> reply(ex, 401, """{"code":"UNAUTHORIZED","message":"nope"}""")
                else -> reply(ex, 200, """{"ok":true}""")
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun `sign in over the real engine captures token and each Set-Cookie separately`() = runBlocking {
        BetterAuthClient { baseUrl = this@RealEngineTest.baseUrl }.use { auth ->
            val outcome = auth.signIn.email("a@b.co", "pw").getOrThrow()
            assertTrue(outcome is SignInOutcome.Authenticated)
            assertEquals("tok.sig=", auth.currentToken())
            assertEquals(setOf("better-auth.session_token", "better-auth.last_used_login_method"), auth.storedSession().cookies.keys)

            auth.ok()
            val second = seen.last()
            assertEquals("Bearer tok.sig=", second.headers["authorization"]?.single())
            assertEquals("better-auth.last_used_login_method=email", second.headers["cookie"]?.single())
            assertEquals("http://127.0.0.1:${server.address.port}", second.headers["origin"]?.single())
        }
    }

    @Test
    fun `base path, json body, accept and user agent are what the server sees`() = runBlocking {
        BetterAuthClient { baseUrl = this@RealEngineTest.baseUrl }.use { auth ->
            auth.signIn.email("a@b.co", "pw")
            val request = seen.first()
            assertEquals("POST", request.method)
            assertEquals("/api/auth/sign-in/email", request.path)
            assertEquals("""{"email":"a@b.co","password":"pw"}""", request.body)
            assertTrue(request.headers["content-type"]!!.single().startsWith("application/json"))
            assertEquals("application/json", request.headers["accept"]?.single())
            assertTrue(request.headers["user-agent"]!!.single().startsWith("better-auth-kt-client/"))
        }
    }

    @Test
    fun `redirects are not followed by the real engine`() = runBlocking {
        BetterAuthClient { baseUrl = this@RealEngineTest.baseUrl }.use { auth ->
            val result = auth.verifyEmail("tok", callbackUrl = "myapp://verified").getOrThrow()
            assertEquals("myapp://verified", result.redirect?.location)
            assertEquals(1, seen.size)
        }
    }

    @Test
    fun `error responses map through the real engine`() = runBlocking {
        BetterAuthClient { baseUrl = this@RealEngineTest.baseUrl }.use { auth ->
            val error = auth.request("GET", "/boom").errorOrNull()
            assertEquals(401, (error as com.timothymugo.betterauth.client.result.BetterAuthError.Api).status)
            assertEquals("UNAUTHORIZED", error.code)
        }
    }

    @Test
    fun `connection failures are reported as Network errors`() = runBlocking {
        server.stop(0)
        BetterAuthClient { baseUrl = this@RealEngineTest.baseUrl; connectTimeoutMillis = 500 }.use { auth ->
            assertTrue(auth.ok().errorOrNull() is com.timothymugo.betterauth.client.result.BetterAuthError.Network)
        }
    }
}
