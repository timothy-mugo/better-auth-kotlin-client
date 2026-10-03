package com.timothymugo.betterauth.client

import com.timothymugo.betterauth.client.model.AuthOrRedirect
import com.timothymugo.betterauth.client.model.AuthResponse
import com.timothymugo.betterauth.client.model.EmailOtpType
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.SignInOutcome
import com.timothymugo.betterauth.client.model.TwoFactorEnrollment
import com.timothymugo.betterauth.client.model.TwoFactorMethod
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialPluginsTest {
    private val authBody = """{"token":"tok","user":$USER_JSON}"""

    @Test
    fun `two factor enable parses uri and backup codes`() = runTest {
        val server = TestServer { json("""{"method":"totp","totpURI":"otpauth://totp/x","backupCodes":["a","b"]}""") }
        val out = server.client().twoFactor.enable("pw", TwoFactorMethod.Totp, issuer = "Qare").success<TwoFactorEnrollment>()
        assertEquals("/api/auth/two-factor/enable", server.last.path)
        assertEquals(JsonPrimitive("totp"), server.last.bodyJson()["method"])
        assertEquals("otpauth://totp/x", out.totpUri)
        assertEquals(listOf("a", "b"), out.backupCodes)
    }

    @Test
    fun `two factor verify completes sign in with token`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/sign-in/email") -> json(
                    """{"twoFactorRedirect":true,"twoFactorMethods":["totp"]}""",
                    headers = arrayOf("Set-Cookie" to listOf("better-auth.two_factor=x.y; Max-Age=600")),
                )
                else -> json(authBody, headers = arrayOf("set-auth-token" to listOf("final.sig")))
            }
        }
        val auth = server.client { origin = "https://app.example.com" }
        auth.signIn.email("a@b.co", "pw").success<SignInOutcome.TwoFactorRequired>()

        val done = auth.twoFactor.verifyTotp("123456", trustDevice = true).success<AuthResponse>()
        assertEquals("/api/auth/two-factor/verify-totp", server.last.path)
        assertEquals("better-auth.two_factor=x.y", server.last.header("Cookie"))
        assertEquals("https://app.example.com", server.last.header("Origin"))
        assertEquals(JsonPrimitive(true), server.last.bodyJson()["trustDevice"])
        assertEquals("u1", done.user?.id)
        assertEquals("final.sig", auth.currentToken())
    }

    @Test
    fun `two factor otp and backup code endpoints`() = runTest {
        val server = TestServer { json(authBody) }
        val auth = server.client()
        auth.twoFactor.sendOtp()
        assertEquals("/api/auth/two-factor/send-otp", server.last.path)
        auth.twoFactor.verifyOtp("999")
        assertEquals(JsonPrimitive("999"), server.last.bodyJson()["code"])
        auth.twoFactor.verifyBackupCode("abcd", disableSession = true)
        assertEquals("/api/auth/two-factor/verify-backup-code", server.last.path)
        assertEquals(JsonPrimitive(true), server.last.bodyJson()["disableSession"])
    }

    @Test
    fun `two factor password protected endpoints`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("get-totp-uri") -> json("""{"totpURI":"otpauth://x"}""")
                req.path.endsWith("generate-backup-codes") -> json("""{"status":true,"backupCodes":["1","2","3"]}""")
                else -> json("""{"status":true}""")
            }
        }
        val auth = server.client()
        assertEquals("otpauth://x", auth.twoFactor.getTotpUri("pw").getOrThrow())
        assertEquals(listOf("1", "2", "3"), auth.twoFactor.generateBackupCodes("pw").getOrThrow())
        assertTrue(auth.twoFactor.disable("pw").success<OperationResult>().success)
        assertEquals(JsonPrimitive("pw"), server.last.bodyJson()["password"])
    }

    @Test
    fun `email otp endpoints use wire types`() = runTest {
        val server = TestServer { json("""{"success":true}""") }
        val otp = server.client().emailOtp
        otp.sendVerificationOtp("a@b.co", EmailOtpType.ForgetPassword)
        assertEquals("/api/auth/email-otp/send-verification-otp", server.last.path)
        assertEquals(JsonPrimitive("forget-password"), server.last.bodyJson()["type"])
        otp.checkVerificationOtp("a@b.co", EmailOtpType.SignIn, "123456")
        assertEquals(JsonPrimitive("sign-in"), server.last.bodyJson()["type"])
        otp.requestPasswordReset("a@b.co")
        assertEquals("/api/auth/email-otp/request-password-reset", server.last.path)
        otp.forgetPassword("a@b.co")
        assertEquals("/api/auth/forget-password/email-otp", server.last.path)
        otp.resetPassword("a@b.co", "123456", "newpw")
        assertEquals(JsonPrimitive("newpw"), server.last.bodyJson()["password"])
        otp.requestEmailChange("n@b.co")
        assertEquals(JsonPrimitive("n@b.co"), server.last.bodyJson()["newEmail"])
        otp.changeEmail("n@b.co", "111111")
        assertEquals("/api/auth/email-otp/change-email", server.last.path)
        assertEquals(JsonPrimitive("111111"), server.last.bodyJson()["otp"])
    }

    @Test
    fun `email otp verify and sign in return the session`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/verify-email")) json("""{"status":true,"token":"t1","user":$USER_JSON}""", headers = arrayOf("set-auth-token" to listOf("t1")))
            else json(authBody)
        }
        val auth = server.client()
        assertEquals("t1", auth.emailOtp.verifyEmail("a@b.co", "123456").success<AuthResponse>().token)
        assertEquals("t1", auth.currentToken())

        val signed = auth.signIn.emailOtp("a@b.co", "654321", name = "Ada").success<SignInOutcome.Authenticated>()
        assertEquals("/api/auth/sign-in/email-otp", server.last.path)
        assertEquals("u1", signed.user?.id)
    }

    @Test
    fun `phone number flows`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/send-otp") -> json("""{"message":"code sent"}""")
                req.path.endsWith("/phone-number/verify") -> json("""{"status":true,"token":"pt","user":$USER_JSON}""")
                req.path.endsWith("/sign-in/phone-number") -> json(authBody)
                else -> json("""{"status":true}""")
            }
        }
        val auth = server.client()
        assertEquals("code sent", auth.phoneNumber.sendOtp("+254700000000").success<OperationResult>().message)
        assertEquals("pt", auth.phoneNumber.verify("+254700000000", "123456", updatePhoneNumber = false).success<AuthResponse>().token)
        assertEquals(JsonPrimitive(false), server.last.bodyJson()["updatePhoneNumber"])
        auth.signIn.phoneNumber("+254700000000", "pw").success<SignInOutcome.Authenticated>()
        assertEquals("/api/auth/sign-in/phone-number", server.last.path)
        auth.phoneNumber.requestPasswordReset("+254700000000")
        assertEquals("/api/auth/phone-number/request-password-reset", server.last.path)
        auth.phoneNumber.resetPassword("111111", "+254700000000", "newpw")
        assertEquals(JsonPrimitive("111111"), server.last.bodyJson()["otp"])
    }

    @Test
    fun `magic link request and verify`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/sign-in/magic-link")) json("""{"status":true}""")
            else json("""{"token":"mt","user":$USER_JSON,"session":$SESSION_JSON}""")
        }
        val auth = server.client()
        assertTrue(auth.signIn.magicLink("a@b.co", callbackUrl = "myapp://x").success<OperationResult>().success)
        assertEquals(JsonPrimitive("myapp://x"), server.last.bodyJson()["callbackURL"])

        val out = auth.magicLink.verify("link-token").success<AuthOrRedirect>()
        assertEquals("GET", server.last.method.value)
        assertEquals("link-token", server.last.url.parameters["token"])
        assertEquals("mt", out.response?.token)
        assertEquals("org1", out.response?.session?.activeOrganizationId)
        assertNull(out.redirect)
    }

    @Test
    fun `magic link verify with callback returns the redirect`() = runTest {
        val server = TestServer { respond("", HttpStatusCode.Found, headersOf("Location", "myapp://signed-in")) }
        val out = server.client().magicLink.verify("tok", callbackUrl = "myapp://signed-in").success<AuthOrRedirect>()
        assertEquals("myapp://signed-in", out.redirect?.location)
        assertNull(out.response)
    }

    @Test
    fun `anonymous sign in and delete`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/sign-in/anonymous")) json("""{"token":"anon","user":{"id":"g1","isAnonymous":true,"name":"Anonymous","email":"temp@x.co"}}""",
                headers = arrayOf("set-auth-token" to listOf("anon")))
            else json("""{"success":true}""")
        }
        val auth = server.client()
        val out = auth.signIn.anonymous().success<SignInOutcome.Authenticated>()
        assertEquals(true, out.user?.isAnonymous)
        assertEquals("anon", auth.currentToken())
        assertTrue(auth.anonymous.deleteUser().success<OperationResult>().success)
        assertNull(auth.currentToken())
    }

    @Test
    fun `one time token`() = runTest {
        val server = TestServer { req ->
            if (req.path.endsWith("/generate")) json("""{"token":"ott-1"}""")
            else json("""{"session":$SESSION_JSON,"user":$USER_JSON}""", headers = arrayOf("set-auth-token" to listOf("fresh")))
        }
        val auth = server.client()
        assertEquals("ott-1", auth.oneTimeToken.generate().getOrThrow())
        assertEquals("GET", server.last.method.value)

        val other = server.client()
        val verified = other.oneTimeToken.verify("ott-1").getOrThrow()
        assertEquals("tok-abc", verified.token)
        assertEquals("fresh", other.currentToken())
        assertEquals(JsonPrimitive("ott-1"), server.last.bodyJson()["token"])
    }
}
