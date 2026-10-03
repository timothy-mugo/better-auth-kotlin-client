@file:UseSerializers(InstantIsoSerializer::class)

package com.timothymugo.betterauth.client.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/**
 * A Better Auth user.
 *
 * Fields your server adds through `user.additionalFields` (for example `isProvider` or `countryId`) are not typed here;
 * they are kept verbatim in [additionalFields]: `user.additionalFields["countryId"]`.
 */
@Serializable(with = UserSerializer::class)
public data class User(
    val id: String,
    val name: String = "",
    val email: String = "",
    val emailVerified: Boolean = false,
    val image: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    /** Admin plugin. */
    val role: String? = null,
    val banned: Boolean? = null,
    val banReason: String? = null,
    val banExpires: Instant? = null,
    /** Two-factor plugin. */
    val twoFactorEnabled: Boolean? = null,
    /** Phone-number plugin. */
    val phoneNumber: String? = null,
    val phoneNumberVerified: Boolean? = null,
    /** Anonymous plugin. */
    val isAnonymous: Boolean? = null,
    /** Every field not modelled above, as the server sent it. */
    val additionalFields: JsonObject = EmptyObject,
)

internal object UserSerializer : JsonObjectSerializer<User>("com.timothymugo.betterauth.client.User") {
    private val known = setOf(
        "id", "name", "email", "emailVerified", "image", "createdAt", "updatedAt", "role", "banned", "banReason",
        "banExpires", "twoFactorEnabled", "phoneNumber", "phoneNumberVerified", "isAnonymous",
    )

    override fun fromJson(obj: JsonObject): User = User(
        id = obj.str("id") ?: error("User.id missing"),
        name = obj.str("name") ?: "",
        email = obj.str("email") ?: "",
        emailVerified = obj.bool("emailVerified") ?: false,
        image = obj.str("image"),
        createdAt = obj.instant("createdAt"),
        updatedAt = obj.instant("updatedAt"),
        role = obj.str("role"),
        banned = obj.bool("banned"),
        banReason = obj.str("banReason"),
        banExpires = obj.instant("banExpires"),
        twoFactorEnabled = obj.bool("twoFactorEnabled"),
        phoneNumber = obj.str("phoneNumber"),
        phoneNumberVerified = obj.bool("phoneNumberVerified"),
        isAnonymous = obj.bool("isAnonymous"),
        additionalFields = obj.without(known),
    )

    override fun toJson(value: User): JsonObject = jsonBody {
        put("id", value.id)
        put("name", value.name)
        put("email", value.email)
        put("emailVerified", value.emailVerified)
        put("image", value.image)
        put("createdAt", value.createdAt)
        put("updatedAt", value.updatedAt)
        put("role", value.role)
        put("banned", value.banned)
        put("banReason", value.banReason)
        put("banExpires", value.banExpires)
        put("twoFactorEnabled", value.twoFactorEnabled)
        put("phoneNumber", value.phoneNumber)
        put("phoneNumberVerified", value.phoneNumberVerified)
        put("isAnonymous", value.isAnonymous)
        putAll(value.additionalFields)
    }
}

/** A server-side session. [token] is the raw session token. */
@Serializable(with = SessionSerializer::class)
public data class Session(
    val id: String,
    val token: String = "",
    val userId: String = "",
    val expiresAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val ipAddress: String? = null,
    val userAgent: String? = null,
    /** Admin plugin: id of the admin impersonating this session's user. */
    val impersonatedBy: String? = null,
    /** Organization plugin. */
    val activeOrganizationId: String? = null,
    val activeTeamId: String? = null,
    val additionalFields: JsonObject = EmptyObject,
)

internal object SessionSerializer : JsonObjectSerializer<Session>("com.timothymugo.betterauth.client.Session") {
    private val known = setOf(
        "id", "token", "userId", "expiresAt", "createdAt", "updatedAt", "ipAddress", "userAgent", "impersonatedBy",
        "activeOrganizationId", "activeTeamId",
    )

    override fun fromJson(obj: JsonObject): Session = Session(
        // Some endpoints (two-factor backup codes) return a trimmed session without `id`.
        id = obj.str("id") ?: "",
        token = obj.str("token") ?: "",
        userId = obj.str("userId") ?: "",
        expiresAt = obj.instant("expiresAt"),
        createdAt = obj.instant("createdAt"),
        updatedAt = obj.instant("updatedAt"),
        ipAddress = obj.str("ipAddress"),
        userAgent = obj.str("userAgent"),
        impersonatedBy = obj.str("impersonatedBy"),
        activeOrganizationId = obj.str("activeOrganizationId"),
        activeTeamId = obj.str("activeTeamId"),
        additionalFields = obj.without(known),
    )

    override fun toJson(value: Session): JsonObject = jsonBody {
        put("id", value.id)
        put("token", value.token)
        put("userId", value.userId)
        put("expiresAt", value.expiresAt)
        put("createdAt", value.createdAt)
        put("updatedAt", value.updatedAt)
        put("ipAddress", value.ipAddress)
        put("userAgent", value.userAgent)
        put("impersonatedBy", value.impersonatedBy)
        put("activeOrganizationId", value.activeOrganizationId)
        put("activeTeamId", value.activeTeamId)
        putAll(value.additionalFields)
    }
}

/** The pair returned by `get-session` and several sign-in style endpoints. */
public data class SessionData(val session: Session, val user: User)

/** A social or credential account linked to the user (`list-accounts`). */
@Serializable
public data class Account(
    val id: String,
    val providerId: String,
    val accountId: String = "",
    val userId: String = "",
    val scopes: List<String> = emptyList(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

/** The user, token and/or session a successful authentication hands back. */
public data class AuthResponse(
    val user: User?,
    /** Session token: `token` from the body, falling back to `session.token`. */
    val token: String?,
    val session: Session?,
)

/** Result of a sign-in that can be interrupted by two-factor authentication. */
public sealed interface SignInOutcome {
    public data class Authenticated(val response: AuthResponse) : SignInOutcome {
        public val user: User? get() = response.user
        public val token: String? get() = response.token
    }

    /**
     * The password was right but the account has 2FA. Call the matching [com.timothymugo.betterauth.client.api.TwoFactorApi]
     * verify method next; the SDK has stored the pending-2FA cookie.
     * [methods] lists what the server offers: `"totp"` and/or `"otp"`.
     */
    public data class TwoFactorRequired(val methods: List<String>) : SignInOutcome
}

/** Result of `sign-in/social`. */
public sealed interface SocialSignInOutcome {
    /**
     * The provider's authorization URL. Open `client.authorizationProxyUrl(url)` in a browser, not [url] itself
     * (see `SignInApi.social`).
     */
    public data class RedirectRequired(val url: String) : SocialSignInOutcome

    /** The `idToken` flow completed without a redirect. */
    public data class Authenticated(val response: AuthResponse) : SocialSignInOutcome
}

/** Generic `{status: true}` / `{success: true}` acknowledgement. */
public data class OperationResult(val success: Boolean, val message: String? = null)

/** A redirect the SDK deliberately did not follow (OAuth callbacks, email links). */
public data class RedirectResponse(val status: Int, val location: String?)

/** Result of `sign-out`. [url] is set when the provider asks for a provider-side logout. */
public data class SignOutResult(val success: Boolean, val url: String? = null, val redirect: Boolean = false)

/** Result of `link-social`. Open [url] when [redirect] is `true`; otherwise the account was linked with an ID token. */
public data class LinkSocialResult(val url: String?, val redirect: Boolean, val status: Boolean?)

/** Result of `verify-email`. [redirect] is set when a `callbackURL` made the server answer with a redirect. */
public data class VerifyEmailResult(
    val success: Boolean,
    val user: User? = null,
    val redirect: RedirectResponse? = null,
)

/** Provider tokens for a linked account (`get-access-token`, `refresh-token`). */
@Serializable
public data class AccessTokenResult(
    val tokenType: String? = null,
    val idToken: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: Instant? = null,
    val refreshTokenExpiresAt: Instant? = null,
)

/** `account-info`: provider profile data for a linked account. */
@Serializable
public data class AccountInfo(
    val user: JsonObject = EmptyObject,
    val account: JsonObject = EmptyObject,
    val data: JsonObject = EmptyObject,
)
