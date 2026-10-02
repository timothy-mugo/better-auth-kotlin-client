package com.qareplus.betterauth

import com.qareplus.betterauth.api.IdTokenCredentials
import com.qareplus.betterauth.api.IdTokenUser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionLifecycleTest {
    private fun signInThenPost(configure: com.qareplus.betterauth.config.BetterAuthConfig.() -> Unit = {}): TestServer {
        val server = TestServer { req ->
            if (req.path.endsWith("/sign-in/email")) json(
                """{"redirect":false,"token":"t","user":$USER_JSON}""",
                headers = arrayOf(
                    "set-auth-token" to listOf("t.sig"),
                    "Set-Cookie" to listOf(
                        "qareplus.last_used_login_method=email; Max-Age=3600",
                        "__Secure-qareplus.session_token_multi-t=t.sig; Max-Age=3600",
                    ),
                ),
            ) else json("""{"status":true}""")
        }
        kotlinx.coroutines.runBlocking {
            val auth = server.client { cookiePrefix = "qareplus"; configure() }
            auth.signIn.email("a@b.co", "pw")
            auth.updateUser(name = "N")
        }
        return server
    }

    @Test
    fun `origin defaults to the base url origin so cookie-carrying POSTs pass the server's origin check`() {
        val server = signInThenPost()
        assertTrue(server.last.header("Cookie")!!.contains("last_used_login_method=email"))
        assertEquals("https://auth.example.com", server.last.header("Origin"))
    }

    @Test
    fun `origin can be overridden or disabled, and keeps a non default port`() {
        assertEquals("myapp://", signInThenPost { origin = "myapp://" }.last.header("Origin"))
        assertNull(signInThenPost { origin = "" }.last.header("Origin"))
        assertEquals("http://localhost:3000", signInThenPost { baseUrl = "http://localhost:3000/api/auth" }.last.header("Origin"))
    }

    @Test
    fun `signOut keeps unrelated cookies such as trust_device and clears session credentials`() = runTest {
        var signedOut = false
        val server = TestServer { req ->
            if (req.path.endsWith("/sign-out")) {
                signedOut = true
                json("""{"success":true}""")
            } else json(
                "{\"ok\":true}",
                headers = arrayOf(
                    "set-auth-token" to listOf("t.sig"),
                    "Set-Cookie" to listOf(
                        "qareplus.session_token=t.sig; Max-Age=3600",
                        "qareplus.session_data=blob; Max-Age=300",
                        "qareplus.account_data=acct; Max-Age=300",
                        "qareplus.trust_device=trust!id; Max-Age=2592000",
                        "qareplus.last_used_login_method=email; Max-Age=3600",
                    ),
                ),
            )
        }
        val auth = server.client(com.qareplus.betterauth.http.AuthMode.Cookie) { cookiePrefix = "qareplus" }
        auth.ok()
        assertEquals("t.sig", auth.currentToken())

        auth.signOut()
        assertTrue(signedOut)
        val after = auth.storedSession()
        assertNull(after.token)
        assertEquals(setOf("qareplus.trust_device", "qareplus.last_used_login_method"), after.cookies.keys)
    }

    @Test
    fun `clearLocalSession still wipes everything`() = runTest {
        val server = TestServer { json("{\"ok\":true}", headers = arrayOf("Set-Cookie" to listOf("a=b; Max-Age=60"))) }
        val auth = server.client()
        auth.ok()
        auth.clearLocalSession()
        assertTrue(auth.storedSession().isEmpty)
    }

    @Test
    fun `browser social flow goes through the authorization proxy and imports the deep link cookie`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/sign-in/social")) json(
                """{"url":"https://accounts.google.com/o/oauth2/auth?state=abc","redirect":true}""",
                headers = arrayOf("Set-Cookie" to listOf("__Secure-qareplus.oauth_state=st%3D1; Max-Age=600")),
            ) else json("""{"session":$SESSION_JSON,"user":$USER_JSON}""")
        }
        val auth = server.client { cookiePrefix = "qareplus" }
        val redirect = auth.signIn.social("google", callbackUrl = "myapp://done")
            .success<com.qareplus.betterauth.model.SocialSignInOutcome.RedirectRequired>()

        val proxy = io.ktor.http.Url(auth.authorizationProxyUrl(redirect.url))
        assertEquals("/api/auth/expo-authorization-proxy", proxy.encodedPath)
        assertEquals(redirect.url, proxy.parameters["authorizationURL"])
        assertEquals("st=1", proxy.parameters["oauthState"])

        // what the server's expo plugin appends to the deep link: the folded Set-Cookie header
        val folded = "__Secure-qareplus.session_token=tok.sig%3D; Max-Age=3600; Path=/; Expires=Wed, 21 Oct 2037 07:28:00 GMT, " +
            "__Secure-qareplus.session_data=blob; Max-Age=300; Path=/"
        auth.completeBrowserSignIn(folded)
        assertEquals("tok.sig=", auth.currentToken())
        assertTrue(auth.storedSession().cookies.keys.containsAll(listOf("__Secure-qareplus.session_token", "__Secure-qareplus.session_data")))

        auth.getSession()
        assertEquals("Bearer tok.sig=", server.last.header("Authorization"))
    }

    @Test
    fun `proxy url omits oauthState when there is none`() = runTest {
        val server = TestServer { json("{\"ok\":true}") }
        val url = io.ktor.http.Url(server.client().authorizationProxyUrl("https://p/auth"))
        assertFalse("oauthState" in url.parameters.names())
    }

    @Test
    fun `apple first sign in profile is sent with the id token`() = runTest {
        val server = TestServer { json("""{"redirect":false,"token":"t","user":$USER_JSON}""") }
        server.client().signIn.social(
            "apple",
            idToken = IdTokenCredentials("jwt", user = IdTokenUser(firstName = "Ada", lastName = "L", email = "a@b.co")),
        )
        val user = (server.last.bodyJson()["idToken"] as JsonObject)["user"] as JsonObject
        assertEquals(JsonPrimitive("a@b.co"), user["email"])
        assertEquals(JsonPrimitive("Ada"), (user["name"] as JsonObject)["firstName"])
    }
}
