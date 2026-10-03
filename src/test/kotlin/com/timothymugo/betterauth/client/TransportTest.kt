package com.timothymugo.betterauth.client

import com.timothymugo.betterauth.client.http.AuthMode
import com.timothymugo.betterauth.client.model.SignInOutcome
import com.timothymugo.betterauth.client.result.BetterAuthError
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
    private val signInBody = """{"redirect":false,"token":"tok-abc","user":$USER_JSON}"""

    @Test
    fun `base path is preserved and body is JSON`() = runTest {
        val server = TestServer { json(signInBody, headers = arrayOf("set-auth-token" to listOf("tok-abc.sig"))) }
        val auth = server.client()

        auth.signIn.email("ada@example.com", "pw", rememberMe = true)

        assertEquals("/api/auth/sign-in/email", server.last.path)
        assertEquals("POST", server.last.method.value)
        assertEquals(JsonPrimitive("ada@example.com"), server.last.bodyJson()["email"])
        assertEquals(JsonPrimitive(true), server.last.bodyJson()["rememberMe"])
    }

    @Test
    fun `trailing slash on base url does not double up`() = runTest {
        val server = TestServer { json("{\"ok\":true}") }
        val auth = server.client { baseUrl = "$BASE/" }
        auth.ok()
        assertEquals("/api/auth/ok", server.last.path)
    }

    @Test
    fun `POST without a body still sends an empty JSON object`() = runTest {
        val server = TestServer { json("""{"success":true}""") }
        server.client().signOut()
        assertEquals("{}", server.last.bodyElement().toString())
    }

    @Test
    fun `bearer token is captured from set-auth-token on sign in and replayed`() = runTest {
        val server = TestServer { req ->
            when (req.path) {
                "/api/auth/sign-in/email" ->
                    json(signInBody, headers = arrayOf("Set-Auth-Token" to listOf("tok-abc.sig")))
                else -> json("""{"session":$SESSION_JSON,"user":$USER_JSON}""")
            }
        }
        val auth = server.client()

        val outcome = auth.signIn.email("ada@example.com", "pw").success<SignInOutcome.Authenticated>()
        assertEquals("u1", outcome.user?.id)
        assertEquals("tok-abc.sig", auth.currentToken())

        auth.getSession()
        assertEquals("Bearer tok-abc.sig", server.last.header("Authorization"))
    }

    @Test
    fun `token is also learned from a later response, not only sign in`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/get-session"))
                json("""{"session":$SESSION_JSON,"user":$USER_JSON}""", headers = arrayOf("set-auth-token" to listOf("rotated")))
            else json("{\"ok\":true}")
        }
        val auth = server.client()
        auth.restoreToken("old")
        auth.getSession()
        assertEquals("rotated", auth.currentToken())
    }

    @Test
    fun `two factor round trip replays the pending cookie with the configured origin`() = runTest {
        val server = TestServer { req ->
            when (req.path) {
                "/api/auth/sign-in/email" -> json(
                    """{"twoFactorRedirect":true,"twoFactorMethods":["totp","otp"]}""",
                    headers = arrayOf("Set-Cookie" to listOf("__Secure-qareplus.two_factor=abc.def%3D; Max-Age=600; Path=/; HttpOnly; Secure; SameSite=None")),
                )
                else -> json("{\"ok\":true}")
            }
        }
        val auth = server.client { cookiePrefix = "qareplus"; origin = "qareplus://app" }

        val outcome = auth.signIn.email("ada@example.com", "pw").success<SignInOutcome.TwoFactorRequired>()
        assertEquals(listOf("totp", "otp"), outcome.methods)

        auth.ok()
        assertEquals("__Secure-qareplus.two_factor=abc.def%3D", server.last.header("Cookie"))
        assertEquals("qareplus://app", server.last.header("Origin"))
    }

    @Test
    fun `bearer mode keeps the session cookie out of the Cookie header, cookie mode sends it`() = runTest {
        fun server() = TestServer {
            json(
                "{\"ok\":true}",
                headers = arrayOf(
                    "Set-Cookie" to listOf(
                        "__Secure-qareplus.session_token=tok.sig; Max-Age=3600; Path=/",
                        "__Secure-qareplus.session_data=blob; Max-Age=300; Path=/",
                        "qareplus.dont_remember=1; Path=/",
                    ),
                ),
            )
        }

        val bearer = server().also { s ->
            val auth = s.client(AuthMode.Bearer) { cookiePrefix = "qareplus" }
            auth.ok()
            auth.ok()
            assertEquals("Bearer tok.sig", s.last.header("Authorization"))
            assertEquals("qareplus.dont_remember=1", s.last.header("Cookie"))
        }

        val cookie = server().also { s ->
            val auth = s.client(AuthMode.Cookie) { cookiePrefix = "qareplus" }
            auth.ok()
            auth.ok()
            assertNull(s.last.header("Authorization"))
            assertTrue(s.last.header("Cookie")!!.contains("__Secure-qareplus.session_token=tok.sig"))
        }
        assertEquals(2, bearer.requests.size)
        assertEquals(2, cookie.requests.size)
    }

    @Test
    fun `Max-Age zero deletes a cookie and clears the token`() = runTest {
        var call = 0
        val server = TestServer {
            call++
            if (call == 1) json("{\"ok\":true}", headers = arrayOf("Set-Cookie" to listOf("better-auth.session_token=tok; Max-Age=3600")))
            else json("{\"ok\":true}", headers = arrayOf("Set-Cookie" to listOf("better-auth.session_token=; Max-Age=0")))
        }
        val auth = server.client()
        auth.ok()
        assertEquals("tok", auth.currentToken())
        auth.ok()
        assertNull(auth.currentToken())
        assertTrue(auth.storedSession().cookies.isEmpty())
    }

    @Test
    fun `multi-session cookies are kept and sent in bearer mode`() = runTest {
        val server = TestServer {
            json("{\"ok\":true}", headers = arrayOf("Set-Cookie" to listOf("better-auth.session_token_multi-abc=xyz; Max-Age=3600")))
        }
        val auth = server.client()
        auth.ok()
        auth.ok()
        assertEquals("better-auth.session_token_multi-abc=xyz", server.last.header("Cookie"))
    }

    @Test
    fun `api errors map to status code and message`() = runTest {
        val server = TestServer {
            json("""{"code":"INVALID_EMAIL_OR_PASSWORD","message":"Invalid email or password"}""", HttpStatusCode.Unauthorized)
        }
        val error = server.client().signIn.email("a@b.co", "bad").failure()
        assertIs<BetterAuthError.Api>(error)
        assertEquals(401, error.status)
        assertEquals("INVALID_EMAIL_OR_PASSWORD", error.code)
        assertEquals("Invalid email or password", error.message)
    }

    @Test
    fun `non-json error bodies still produce an api error`() = runTest {
        val server = TestServer { respond("Bad gateway", HttpStatusCode.BadGateway) }
        val error = server.client().ok().failure()
        assertIs<BetterAuthError.Api>(error)
        assertEquals(502, error.status)
        assertEquals("Bad gateway", error.message)
    }

    @Test
    fun `transport failures map to Network`() = runTest {
        val server = TestServer { throw java.io.IOException("boom") }
        val error = server.client().ok().failure()
        assertIs<BetterAuthError.Network>(error)
        assertEquals("boom", error.message)
    }

    @Test
    fun `unexpected body shape maps to Decoding`() = runTest {
        val server = TestServer { json("""{"user":{"nope":1}}""") }
        val error = server.client().signUp.email("n", "e@x.co", "pw").failure()
        assertIs<BetterAuthError.Decoding>(error)
    }

    @Test
    fun `redirects are not followed`() = runTest {
        val server = TestServer {
            respond("", HttpStatusCode.Found, io.ktor.http.headersOf("Location", "myapp://verified"))
        }
        val result = server.client().verifyEmail("tok", callbackUrl = "myapp://verified").success<com.timothymugo.betterauth.client.model.VerifyEmailResult>()
        assertEquals(1, server.requests.size)
        assertEquals("myapp://verified", result.redirect?.location)
        assertEquals(302, result.redirect?.status)
        assertEquals("/api/auth/verify-email", server.last.path)
        assertEquals("tok", server.last.url.parameters["token"])
        assertFalse(result.user != null)
    }

    @Test
    fun `withSession is isolated from the parent client`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/get-session")) json("""{"session":$SESSION_JSON,"user":$USER_JSON}""",
                headers = arrayOf("set-auth-token" to listOf("scoped-rotated")))
            else json("{\"ok\":true}")
        }
        val parent = server.client()
        parent.restoreToken("parent-token")

        val scoped = parent.withSession("user-token")
        scoped.getSession()
        assertEquals("Bearer user-token", server.last.header("Authorization"))
        assertEquals("scoped-rotated", scoped.currentToken())
        assertEquals("parent-token", parent.currentToken())

        scoped.close()
        parent.ok()
        assertEquals("Bearer parent-token", server.last.header("Authorization"))
    }

    @Test
    fun `custom headers and user agent are sent`() = runTest {
        val server = TestServer { json("{\"ok\":true}") }
        val auth = server.client {
            userAgent = "my-app/1.0"
            headers["x-api-key"] = "k"
            expoOrigin = "myapp://"
        }
        auth.ok()
        assertEquals("my-app/1.0", server.last.header("User-Agent"))
        assertEquals("k", server.last.header("x-api-key"))
        assertEquals("myapp://", server.last.header("expo-origin"))
    }
}
