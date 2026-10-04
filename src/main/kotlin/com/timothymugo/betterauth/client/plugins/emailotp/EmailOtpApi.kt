package com.timothymugo.betterauth.client.plugins.emailotp

import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.api.*
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginKey
import com.timothymugo.betterauth.client.model.SignInOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.AuthResponse
import com.timothymugo.betterauth.client.model.EmailOtpType
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.result.BetterAuthResult

/** `client.emailOtp`. Signing in with a code is `client.signIn.emailOtp(...)`. */
public class EmailOtpApi internal constructor(private val t: Transport) {
    public suspend fun sendVerificationOtp(email: String, type: EmailOtpType): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/email-otp/send-verification-otp",
            jsonBody {
                put("email", email)
                put("type", type.wire)
            },
        )

    /** Checks a code without consuming it. */
    public suspend fun checkVerificationOtp(
        email: String,
        type: EmailOtpType,
        otp: String,
    ): BetterAuthResult<OperationResult> = t.postForResult(
        "/email-otp/check-verification-otp",
        jsonBody {
            put("email", email)
            put("type", type.wire)
            put("otp", otp)
        },
    )

    /** Verifies the address. The response carries a token when the server auto-signs-in on verification. */
    public suspend fun verifyEmail(email: String, otp: String): BetterAuthResult<AuthResponse> = t.post(
        "/email-otp/verify-email",
        jsonBody {
            put("email", email)
            put("otp", otp)
        },
    ) { it.toAuthResponse() }

    public suspend fun requestPasswordReset(email: String): BetterAuthResult<OperationResult> =
        t.postForResult("/email-otp/request-password-reset", jsonBody { put("email", email) })

    /** Older name for [requestPasswordReset]. */
    public suspend fun forgetPassword(email: String): BetterAuthResult<OperationResult> =
        t.postForResult("/forget-password/email-otp", jsonBody { put("email", email) })

    public suspend fun resetPassword(email: String, otp: String, password: String): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/email-otp/reset-password",
            jsonBody {
                put("email", email)
                put("otp", otp)
                put("password", password)
            },
        )

    /** Starts an email change; depending on server config [otp] (sent to the current address) is required. */
    public suspend fun requestEmailChange(newEmail: String, otp: String? = null): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/email-otp/request-email-change",
            jsonBody {
                put("newEmail", newEmail)
                put("otp", otp)
            },
        )

    public suspend fun changeEmail(newEmail: String, otp: String): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/email-otp/change-email",
            jsonBody {
                put("newEmail", newEmail)
                put("otp", otp)
            },
        )

    internal suspend fun signIn(email: String, otp: String, name: String?, image: String?): BetterAuthResult<SignInOutcome> = t.post(
        "/sign-in/email-otp",
        jsonBody {
            put("email", email)
            put("otp", otp)
            put("name", name)
            put("image", image)
        },
    ) { it.toSignInOutcome() }
}

private val EmailOtpKey = PluginKey<EmailOtpApi>("emailOtp", "emailOtpClient()")

private object EmailOtpPlugin : ClientPlugin<EmailOtpApi> {
    override val key: PluginKey<EmailOtpApi> = EmailOtpKey

    override fun createApi(context: PluginContext): EmailOtpApi = EmailOtpApi(context.transport)
}

/**
 * Registers the Email OTP plugin: `BetterAuthClient { plugins(emailOtpClient()) }`. Its API is then `client.emailOtp`.
 */
public fun emailOtpClient(): ClientPlugin<EmailOtpApi> = EmailOtpPlugin

/** The Email OTP API. Throws if [emailOtpClient] was not registered with `plugins(...)`. */
public val BetterAuthClient.emailOtp: EmailOtpApi get() = plugin(EmailOtpKey)

/** Signs in with a code sent by `client.emailOtp.sendVerificationOtp(email, EmailOtpType.SignIn)`. */
public suspend fun SignInApi.emailOtp(
    email: String,
    otp: String,
    name: String? = null,
    image: String? = null,
): BetterAuthResult<SignInOutcome> = client.emailOtp.signIn(email, otp, name, image)
