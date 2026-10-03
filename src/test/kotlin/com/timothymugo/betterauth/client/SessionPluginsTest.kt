package com.timothymugo.betterauth.client

import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.PasskeyRegistration
import com.timothymugo.betterauth.client.model.SessionData
import com.timothymugo.betterauth.client.model.SignInOutcome
import com.timothymugo.betterauth.client.model.WebAuthnOptions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionPluginsTest {
    private val passkeyJson = """{"id":"pk1","name":"Pixel","credentialID":"cred","userId":"u1","publicKey":"pub","counter":3,
        "deviceType":"multiDevice","backedUp":true,"transports":"internal","createdAt":"2026-01-02T03:04:05.000Z"}"""

    @Test
    fun `multi session lists switches and revokes using jar cookies`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/sign-in/email") -> json(
                    """{"redirect":false,"token":"t1","user":$USER_JSON}""",
                    headers = arrayOf(
                        "set-auth-token" to listOf("t1.sig"),
                        "Set-Cookie" to listOf("better-auth.session_token=t1.sig; Max-Age=3600", "better-auth.session_token_multi-t1=t1.sig; Max-Age=3600"),
                    ),
                )
                req.path.endsWith("/list-device-sessions") -> json("""[{"session":$SESSION_JSON,"user":$USER_JSON}]""")
                req.path.endsWith("/set-active") -> json(
                    """{"session":$SESSION_JSON,"user":$USER_JSON}""",
                    headers = arrayOf("set-auth-token" to listOf("t2.sig")),
                )
                else -> json("""{"status":true}""")
            }
        }
        val auth = server.client()
        auth.signIn.email("a@b.co", "pw").success<SignInOutcome.Authenticated>()

        val sessions = auth.multiSession.listDeviceSessions().getOrThrow()
        assertEquals(1, sessions.size)
        // the server reads the device sessions from the multi-session cookie, so it must be replayed in bearer mode
        assertEquals("better-auth.session_token_multi-t1=t1.sig", server.last.header("Cookie"))
        assertEquals("Bearer t1.sig", server.last.header("Authorization"))

        auth.multiSession.setActive("t2").success<SessionData>()
        assertEquals(JsonPrimitive("t2"), server.last.bodyJson()["sessionToken"])
        assertEquals("t2.sig", auth.currentToken())

        assertTrue(auth.multiSession.revoke("t1").success<OperationResult>().success)
        assertEquals("/api/auth/multi-session/revoke", server.last.path)
    }

    @Test
    fun `jwt token and jwks`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/jwks")) json("""{"keys":[{"kid":"k1","kty":"OKP","alg":"EdDSA","crv":"Ed25519","x":"abc"}]}""")
            else json("""{"token":"jwt.value"}""", headers = arrayOf("set-auth-jwt" to listOf("jwt.value")))
        }
        val auth = server.client()
        assertEquals("jwt.value", auth.jwt.token().getOrThrow())
        assertEquals("jwt.value", auth.storedSession().jwt)
        val key = auth.jwt.jwks().getOrThrow().single()
        assertEquals("k1", key.kid)
        assertEquals("EdDSA", key.alg)
        assertEquals(JsonPrimitive("Ed25519"), key.raw["crv"])
    }

    @Test
    fun `passkey registration round trip`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/generate-register-options") -> json("""{"challenge":"abc","rp":{"id":"qareplus.com"}}""")
                req.path.endsWith("/verify-registration") -> json(passkeyJson)
                req.path.endsWith("/list-user-passkeys") -> json("[$passkeyJson]")
                req.path.endsWith("/update-passkey") -> json("""{"passkey":$passkeyJson}""")
                else -> json("""{"status":true}""")
            }
        }
        val auth = server.client()
        val options = auth.passkey.generateRegisterOptions(authenticatorAttachment = "platform", name = "Pixel").success<WebAuthnOptions>()
        assertEquals("platform", server.last.url.parameters["authenticatorAttachment"])
        assertEquals(JsonPrimitive("abc"), (options.json as kotlinx.serialization.json.JsonObject)["challenge"])

        val reg = auth.passkey.verifyRegistration(buildJsonObject { put("id", "cred") }, name = "Pixel").success<PasskeyRegistration>()
        assertEquals("cred", reg.passkey.credentialID)
        assertEquals(3, reg.passkey.counter)
        assertEquals(JsonPrimitive("cred"), (server.last.bodyJson()["response"] as kotlinx.serialization.json.JsonObject)["id"])

        assertEquals("pk1", auth.passkey.listUserPasskeys().getOrThrow().single().id)
        assertEquals("pk1", auth.passkey.updatePasskey("pk1", "New").getOrThrow().id)
        assertTrue(auth.passkey.deletePasskey("pk1").success<OperationResult>().success)
    }

    @Test
    fun `passkey sign in`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/generate-authenticate-options")) json("""{"challenge":"c"}""")
            else json("""{"session":$SESSION_JSON,"user":$USER_JSON}""", headers = arrayOf("set-auth-token" to listOf("pk.sig")))
        }
        val auth = server.client()
        auth.passkey.generateAuthenticateOptions().success<WebAuthnOptions>()
        val out = auth.signIn.passkey(buildJsonObject { put("id", "cred") }).success<SignInOutcome.Authenticated>()
        assertEquals("tok-abc", out.token)
        assertEquals("pk.sig", auth.currentToken())
    }
}
