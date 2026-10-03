package com.timothymugo.betterauth.client.api

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.SignInOutcome
import com.timothymugo.betterauth.client.model.SocialSignInOutcome
import com.timothymugo.betterauth.client.model.bool
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.model.str
import com.timothymugo.betterauth.client.result.BetterAuthResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Profile data Apple only sends on the very first sign-in; pass it along so the server can store the name. */
public data class IdTokenUser(val firstName: String? = null, val lastName: String? = null, val email: String? = null)

/** A provider-issued ID token for native social sign-in (Google One Tap / Sign in with Apple). */
public data class IdTokenCredentials(
    val token: String,
    val nonce: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Epoch milliseconds, if the provider reports an expiry. */
    val expiresAt: Long? = null,
    val user: IdTokenUser? = null,
)

/** Provider ids accepted by `sign-in/social` and `link-social`. Any string works, these are just the built-in ones. */
public object SocialProviders {
    public const val APPLE: String = "apple"
    public const val GOOGLE: String = "google"
    public const val GITHUB: String = "github"
    public const val FACEBOOK: String = "facebook"
    public const val MICROSOFT: String = "microsoft"
    public const val DISCORD: String = "discord"
    public const val TWITTER: String = "twitter"
    public const val LINKEDIN: String = "linkedin"
}

internal fun IdTokenCredentials.toJson(): JsonObject = jsonBody {
    put("token", token)
    put("nonce", nonce)
    put("accessToken", accessToken)
    put("refreshToken", refreshToken)
    put("expiresAt", expiresAt)
    user?.let { u ->
        put(
            "user",
            jsonBody {
                if (u.firstName != null || u.lastName != null) {
                    put("name", jsonBody { put("firstName", u.firstName); put("lastName", u.lastName) })
                }
                put("email", u.email)
            },
        )
    }
}

internal fun JsonElement.toSignInOutcome(): SignInOutcome {
    val o = asObject()
    if (o.bool("twoFactorRedirect") == true) {
        return SignInOutcome.TwoFactorRequired(o.strings("twoFactorMethods"))
    }
    return SignInOutcome.Authenticated(toAuthResponse())
}

/** `client.signIn` */
public class SignInApi internal constructor(private val t: Transport) {
    /**
     * Signs in with email and password.
     *
     * Returns [SignInOutcome.TwoFactorRequired] when the account has two-factor authentication; finish with
     * `client.twoFactor.verifyTotp(...)`, `verifyOtp(...)` or `verifyBackupCode(...)`.
     */
    public suspend fun email(
        email: String,
        password: String,
        callbackUrl: String? = null,
        rememberMe: Boolean? = null,
    ): BetterAuthResult<SignInOutcome> = t.post(
        "/sign-in/email",
        jsonBody {
            put("email", email)
            put("password", password)
            put("callbackURL", callbackUrl)
            put("rememberMe", rememberMe)
        },
    ) { it.toSignInOutcome() }

    /**
     * Starts or completes a social sign-in.
     *
     * - Without [idToken]: returns [SocialSignInOutcome.RedirectRequired]. Do not open that URL directly: the OAuth
     *   state cookie lives in the SDK's jar, not the browser's. Wrap it with `client.authorizationProxyUrl(url)`, open
     *   the result in a browser (Custom Tabs), and when your deep link fires pass its `cookie` query parameter to
     *   `client.completeBrowserSignIn(cookie)`. This is the flow of Better Auth's Expo plugin, so the server needs
     *   `expo()` and your deep link in `trustedOrigins`.
     * - With [idToken] (native Google/Apple sign-in): signs in directly and returns
     *   [SocialSignInOutcome.Authenticated].
     */
    public suspend fun social(
        provider: String,
        callbackUrl: String? = null,
        newUserCallbackUrl: String? = null,
        errorCallbackUrl: String? = null,
        idToken: IdTokenCredentials? = null,
        scopes: List<String>? = null,
        requestSignUp: Boolean? = null,
        loginHint: String? = null,
        disableRedirect: Boolean? = null,
        additionalData: JsonObject? = null,
    ): BetterAuthResult<SocialSignInOutcome> = t.post(
        "/sign-in/social",
        jsonBody {
            put("provider", provider)
            put("callbackURL", callbackUrl)
            put("newUserCallbackURL", newUserCallbackUrl)
            put("errorCallbackURL", errorCallbackUrl)
            put("idToken", idToken?.toJson())
            putStrings("scopes", scopes)
            put("requestSignUp", requestSignUp)
            put("loginHint", loginHint)
            put("disableRedirect", disableRedirect)
            put("additionalData", additionalData)
        },
    ) { element ->
        val o = element.asObject()
        val url = o.str("url")
        if (url != null && o.bool("redirect") != false) {
            SocialSignInOutcome.RedirectRequired(url)
        } else {
            SocialSignInOutcome.Authenticated(element.toAuthResponse())
        }
    }

    /** Creates a guest user (anonymous plugin). */
    public suspend fun anonymous(): BetterAuthResult<SignInOutcome> =
        t.post("/sign-in/anonymous") { it.toSignInOutcome() }

    /** Signs in with phone number and password (phone-number plugin). */
    public suspend fun phoneNumber(
        phoneNumber: String,
        password: String,
        rememberMe: Boolean? = null,
    ): BetterAuthResult<SignInOutcome> = t.post(
        "/sign-in/phone-number",
        jsonBody {
            put("phoneNumber", phoneNumber)
            put("password", password)
            put("rememberMe", rememberMe)
        },
    ) { it.toSignInOutcome() }

    /** Emails a one-click sign-in link (magic-link plugin). Complete it with `client.magicLink.verify(token)`. */
    public suspend fun magicLink(
        email: String,
        name: String? = null,
        callbackUrl: String? = null,
        newUserCallbackUrl: String? = null,
        errorCallbackUrl: String? = null,
        metadata: JsonObject? = null,
    ): BetterAuthResult<OperationResult> = t.postForResult(
        "/sign-in/magic-link",
        jsonBody {
            put("email", email)
            put("name", name)
            put("callbackURL", callbackUrl)
            put("newUserCallbackURL", newUserCallbackUrl)
            put("errorCallbackURL", errorCallbackUrl)
            put("metadata", metadata)
        },
    )

    /** Signs in with a code sent by `client.emailOtp.sendVerificationOtp(email, EmailOtpType.SignIn)`. */
    public suspend fun emailOtp(
        email: String,
        otp: String,
        name: String? = null,
        image: String? = null,
    ): BetterAuthResult<SignInOutcome> = t.post(
        "/sign-in/email-otp",
        jsonBody {
            put("email", email)
            put("otp", otp)
            put("name", name)
            put("image", image)
        },
    ) { it.toSignInOutcome() }

    /**
     * Signs in with a passkey. [response] is the credential the platform returned for the options from
     * `client.passkey.generateAuthenticateOptions()`.
     */
    public suspend fun passkey(response: JsonElement): BetterAuthResult<SignInOutcome> =
        t.post("/passkey/verify-authentication", jsonBody { put("response", response) }) { it.toSignInOutcome() }
}
