package com.qareplus.betterauth.api

import com.qareplus.betterauth.http.Transport
import com.qareplus.betterauth.model.AuthResponse
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.jsonBody
import com.qareplus.betterauth.result.BetterAuthResult

/** `client.phoneNumber`. Signing in with phone and password is `client.signIn.phoneNumber(...)`. */
public class PhoneNumberApi internal constructor(private val t: Transport) {
    /** Sends a verification code by SMS. [OperationResult.message] is the server's note, usually `"code sent"`. */
    public suspend fun sendOtp(phoneNumber: String): BetterAuthResult<OperationResult> =
        t.postForResult("/phone-number/send-otp", jsonBody { put("phoneNumber", phoneNumber) })

    /**
     * Verifies the code. Depending on server config this also signs the user in (token in the result) unless
     * [disableSession] is set; [updatePhoneNumber] changes the signed-in user's number instead.
     */
    public suspend fun verify(
        phoneNumber: String,
        code: String,
        disableSession: Boolean? = null,
        updatePhoneNumber: Boolean? = null,
    ): BetterAuthResult<AuthResponse> = t.post(
        "/phone-number/verify",
        jsonBody {
            put("phoneNumber", phoneNumber)
            put("code", code)
            put("disableSession", disableSession)
            put("updatePhoneNumber", updatePhoneNumber)
        },
    ) { it.toAuthResponse() }

    public suspend fun requestPasswordReset(phoneNumber: String): BetterAuthResult<OperationResult> =
        t.postForResult("/phone-number/request-password-reset", jsonBody { put("phoneNumber", phoneNumber) })

    public suspend fun resetPassword(otp: String, phoneNumber: String, newPassword: String): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/phone-number/reset-password",
            jsonBody {
                put("otp", otp)
                put("phoneNumber", phoneNumber)
                put("newPassword", newPassword)
            },
        )
}
