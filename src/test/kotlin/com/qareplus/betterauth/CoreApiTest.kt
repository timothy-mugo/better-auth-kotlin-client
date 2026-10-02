package com.qareplus.betterauth

import com.qareplus.betterauth.api.IdTokenCredentials
import com.qareplus.betterauth.model.AuthResponse
import com.qareplus.betterauth.model.LinkSocialResult
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.SessionData
import com.qareplus.betterauth.model.SignOutResult
import com.qareplus.betterauth.model.SocialSignInOutcome
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreApiTest {
    @Test
    fun `sign up merges additional fields and parses user plus passthrough`() = runTest {
        val server = TestServer { json("""{"token":"t","user":$USER_JSON}""") }
        val result = server.client().signUp.email(
            name = "Ada",
            email = "ada@example.com",
            password = "pw",
            additionalFields = buildJsonObject {
                put("isProvider", true)
                put("tosAccepted", true)
            },
        ).success<AuthResponse>()

        val body = server.last.bodyJson()
        assertEquals("/api/auth/sign-up/email", server.last.path)
        assertEquals(JsonPrimitive(true), body["isProvider"])
        assertEquals(JsonPrimitive(true), body["tosAccepted"])
        assertEquals("t", result.token)
        assertEquals("u1", result.user?.id)
        assertEquals(JsonPrimitive("ke"), result.user?.additionalFields?.get("countryId"))
        assertEquals(JsonPrimitive(true), result.user?.additionalFields?.get("isProvider"))
    }

    @Test
    fun `sign up without token (email verification required)`() = runTest {
        val server = TestServer { json("""{"token":null,"user":$USER_JSON}""") }
        val result = server.client().signUp.email("Ada", "ada@example.com", "pw").success<AuthResponse>()
        assertNull(result.token)
    }

    @Test
    fun `getSession returns null for a JSON null body`() = runTest {
        val server = TestServer { json("null") }
        val result = server.client().getSession()
        assertNull(result.getOrNull())
        assertTrue(result.isSuccess)
    }

    @Test
    fun `getSession decodes session and user with timestamps`() = runTest {
        val server = TestServer { json("""{"session":$SESSION_JSON,"user":$USER_JSON}""") }
        val data = server.client().getSession(disableCookieCache = true).success<SessionData>()
        assertEquals("tok-abc", data.session.token)
        assertEquals("org1", data.session.activeOrganizationId)
        assertEquals("2026-02-01T00:00:00Z", data.session.expiresAt.toString())
        assertEquals("ada@example.com", data.user.email)
        assertEquals("true", server.last.url.parameters["disableCookieCache"])
        assertNull(server.last.url.parameters["disableRefresh"])
    }

    @Test
    fun `signOut clears the local session on success`() = runTest {
        val server = TestServer { json("""{"success":true}""") }
        val auth = server.client()
        auth.restoreToken("tok")
        val out = auth.signOut().success<SignOutResult>()
        assertTrue(out.success)
        assertNull(auth.currentToken())
    }

    @Test
    fun `signOut keeps the session when the server fails`() = runTest {
        val server = TestServer { json("""{"message":"oops"}""", HttpStatusCode.InternalServerError) }
        val auth = server.client()
        auth.restoreToken("tok")
        auth.signOut().failure()
        assertEquals("tok", auth.currentToken())
    }

    @Test
    fun `social sign in without id token returns a redirect url`() = runTest {
        val server = TestServer { json("""{"url":"https://accounts.google.com/auth","redirect":true}""") }
        val out = server.client().signIn.social("google", callbackUrl = "myapp://home").success<SocialSignInOutcome.RedirectRequired>()
        assertEquals("https://accounts.google.com/auth", out.url)
        assertEquals(JsonPrimitive("google"), server.last.bodyJson()["provider"])
        assertEquals(JsonPrimitive("myapp://home"), server.last.bodyJson()["callbackURL"])
    }

    @Test
    fun `social sign in with id token authenticates`() = runTest {
        val server = TestServer { json("""{"redirect":false,"token":"tok","url":null,"user":$USER_JSON}""") }
        val out = server.client().signIn.social("google", idToken = IdTokenCredentials(token = "jwt", nonce = "n"))
            .success<SocialSignInOutcome.Authenticated>()
        assertEquals("tok", out.response.token)
        val idToken = server.last.bodyJson()["idToken"] as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("jwt"), idToken["token"])
        assertEquals(JsonPrimitive("n"), idToken["nonce"])
    }

    @Test
    fun `changePassword returns the rotated token`() = runTest {
        val server = TestServer { json("""{"token":"new","user":$USER_JSON}""", headers = arrayOf("set-auth-token" to listOf("new"))) }
        val auth = server.client()
        val out = auth.changePassword("old", "newpw", revokeOtherSessions = true).success<AuthResponse>()
        assertEquals("new", out.token)
        assertEquals("new", auth.currentToken())
        assertEquals(JsonPrimitive(true), server.last.bodyJson()["revokeOtherSessions"])
    }

    @Test
    fun `status style acknowledgements`() = runTest {
        val server = TestServer { json("""{"status":true}""") }
        val auth = server.client()
        assertTrue(auth.revokeOtherSessions().success<OperationResult>().success)
        assertEquals("/api/auth/revoke-other-sessions", server.last.path)
        assertTrue(auth.updateUser(name = "N", additionalFields = buildJsonObject { put("countryId", "ke") }).success<OperationResult>().success)
        assertEquals(JsonPrimitive("ke"), server.last.bodyJson()["countryId"])
        assertTrue(auth.requestPasswordReset("a@b.co", "myapp://reset").success<OperationResult>().success)
        assertEquals(JsonPrimitive("myapp://reset"), server.last.bodyJson()["redirectTo"])
    }

    @Test
    fun `deleteUser clears the local session`() = runTest {
        val server = TestServer { json("""{"success":true,"message":"User deleted"}""") }
        val auth = server.client()
        auth.restoreToken("tok")
        auth.deleteUser(password = "pw").success<OperationResult>()
        assertNull(auth.currentToken())
    }

    @Test
    fun `listSessions and listAccounts`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/list-sessions")) json("[$SESSION_JSON]")
            else json("""[{"id":"a1","providerId":"google","accountId":"g1","userId":"u1","scopes":["email"],"createdAt":"2026-01-02T03:04:05.000Z"}]""")
        }
        val auth = server.client()
        assertEquals(1, auth.listSessions().getOrThrow().size)
        val accounts = auth.listAccounts().getOrThrow()
        assertEquals("google", accounts.single().providerId)
        assertEquals(listOf("email"), accounts.single().scopes)
    }

    @Test
    fun `linkSocial and getAccessToken`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/link-social")) json("""{"url":"https://p/auth","redirect":true}""")
            else json("""{"accessToken":"at","tokenType":"Bearer","accessTokenExpiresAt":"2026-01-02T03:04:05.000Z"}""")
        }
        val auth = server.client()
        assertEquals("https://p/auth", auth.linkSocial("github").success<LinkSocialResult>().url)
        val tokens = auth.getAccessToken(accountId = "a1").getOrThrow()
        assertEquals("at", tokens.accessToken)
        assertEquals(JsonPrimitive("a1"), server.last.bodyJson()["accountId"])
        auth.getAccessToken(useAccountCookie = true)
        assertEquals(JsonPrimitive(true), server.last.bodyJson()["useAccountCookie"])
        assertNull(server.last.bodyJson()["accountId"])
    }

    @Test
    fun `escape hatch decodes arbitrary endpoints`() = runTest {
        val server = TestServer { json("""{"hello":"world"}""") }
        val auth = server.client()
        val raw = auth.request("get", "/custom/thing", query = mapOf("a" to "1")).getOrThrow()
        assertEquals("""{"hello":"world"}""", raw.toString())
        assertEquals("GET", server.last.method.value)
        assertEquals("1", server.last.url.parameters["a"])

        val typed = auth.request<Map<String, String>>("POST", "/custom/thing", JsonArray(emptyList())).getOrThrow()
        assertEquals("world", typed["hello"])
    }
}
