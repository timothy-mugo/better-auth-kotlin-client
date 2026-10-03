package com.timothymugo.betterauth.client.api

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.AuthResponse
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.TwoFactorEnrollment
import com.timothymugo.betterauth.client.model.TwoFactorMethod
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.model.str
import com.timothymugo.betterauth.client.result.BetterAuthResult

/**
 * `client.twoFactor`
 *
 * The *verify* methods finish a sign-in that returned
 * [com.timothymugo.betterauth.client.model.SignInOutcome.TwoFactorRequired]; they rely on the pending-2FA cookie the SDK stored.
 */
public class TwoFactorApi internal constructor(private val t: Transport) {
    /**
     * Enrols a second factor for the signed-in user. For [TwoFactorMethod.Totp] (default) the result carries the
     * `otpauth://` URI for an authenticator app and one-time backup codes.
     */
    public suspend fun enable(
        password: String,
        method: TwoFactorMethod? = null,
        issuer: String? = null,
    ): BetterAuthResult<TwoFactorEnrollment> = t.post(
        "/two-factor/enable",
        jsonBody {
            put("password", password)
            put("method", method?.wire)
            put("issuer", issuer)
        },
    ) { element ->
        val o = element.asObject()
        TwoFactorEnrollment(
            method = o.str("method") ?: method?.wire ?: "totp",
            totpUri = o.str("totpURI"),
            backupCodes = o.strings("backupCodes"),
        )
    }

    public suspend fun disable(password: String): BetterAuthResult<OperationResult> =
        t.postForResult("/two-factor/disable", jsonBody { put("password", password) })

    /** The TOTP `otpauth://` URI, e.g. to show a QR code again. */
    public suspend fun getTotpUri(password: String): BetterAuthResult<String?> =
        t.post("/two-factor/get-totp-uri", jsonBody { put("password", password) }) { it.asObject().str("totpURI") }

    /** Verifies an authenticator-app code and completes the sign-in. [trustDevice] skips 2FA on this device next time. */
    public suspend fun verifyTotp(code: String, trustDevice: Boolean? = null): BetterAuthResult<AuthResponse> =
        t.post(
            "/two-factor/verify-totp",
            jsonBody {
                put("code", code)
                put("trustDevice", trustDevice)
            },
        ) { it.toAuthResponse() }

    /** Asks the server to send a code through the configured channel (email/SMS). */
    public suspend fun sendOtp(trustDevice: Boolean? = null): BetterAuthResult<OperationResult> =
        t.postForResult("/two-factor/send-otp", jsonBody { put("trustDevice", trustDevice) })

    public suspend fun verifyOtp(code: String, trustDevice: Boolean? = null): BetterAuthResult<AuthResponse> =
        t.post(
            "/two-factor/verify-otp",
            jsonBody {
                put("code", code)
                put("trustDevice", trustDevice)
            },
        ) { it.toAuthResponse() }

    public suspend fun verifyBackupCode(
        code: String,
        disableSession: Boolean? = null,
        trustDevice: Boolean? = null,
    ): BetterAuthResult<AuthResponse> = t.post(
        "/two-factor/verify-backup-code",
        jsonBody {
            put("code", code)
            put("disableSession", disableSession)
            put("trustDevice", trustDevice)
        },
    ) { it.toAuthResponse() }

    /** Replaces the user's backup codes. */
    public suspend fun generateBackupCodes(password: String): BetterAuthResult<List<String>> =
        t.post("/two-factor/generate-backup-codes", jsonBody { put("password", password) }) {
            it.asObject().strings("backupCodes")
        }
}
