@file:UseSerializers(InstantIsoSerializer::class)

package com.qareplus.betterauth.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Either an authenticated response or a redirect that was not followed (links that carry a `callbackURL`). */
public data class AuthOrRedirect(val response: AuthResponse? = null, val redirect: RedirectResponse? = null)

/** Which second factor to enrol. */
public enum class TwoFactorMethod(internal val wire: String) {
    Totp("totp"),
    Otp("otp"),
}

/** Result of `two-factor/enable`. [totpUri] and [backupCodes] are only present for [TwoFactorMethod.Totp]. */
public data class TwoFactorEnrollment(
    val method: String,
    val totpUri: String? = null,
    val backupCodes: List<String> = emptyList(),
)

/** The `type` of an email OTP. */
public enum class EmailOtpType(internal val wire: String) {
    EmailVerification("email-verification"),
    SignIn("sign-in"),
    ForgetPassword("forget-password"),
    ChangeEmail("change-email"),
}

/** One entry of the JSON Web Key Set. Algorithm specific members are in [raw]. */
public data class JsonWebKey(
    val kid: String,
    val kty: String,
    val alg: String? = null,
    val use: String? = null,
    val raw: JsonObject = EmptyObject,
)

/** A passkey registered by the user. */
@Serializable
public data class Passkey(
    val id: String,
    val name: String? = null,
    val credentialID: String = "",
    val userId: String = "",
    val publicKey: String = "",
    val counter: Long = 0,
    val deviceType: String? = null,
    val backedUp: Boolean = false,
    val transports: String? = null,
    val aaguid: String? = null,
    val createdAt: Instant? = null,
)

/** WebAuthn options returned by the server, passed through untouched so you can hand them to Credential Manager. */
public data class WebAuthnOptions(val json: JsonElement)

/** Result of `passkey/verify-registration`. [session] and [user] are set when `createSession` was requested. */
public data class PasskeyRegistration(val passkey: Passkey, val session: Session? = null, val user: User? = null)
