package com.timothymugo.betterauth.client.api

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
}
