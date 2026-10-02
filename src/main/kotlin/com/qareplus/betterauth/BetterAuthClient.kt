package com.qareplus.betterauth

import com.qareplus.betterauth.api.AdminApi
import com.qareplus.betterauth.api.AnonymousApi
import com.qareplus.betterauth.api.EmailOtpApi
import com.qareplus.betterauth.api.IdTokenCredentials
import com.qareplus.betterauth.api.JwtApi
import com.qareplus.betterauth.api.MagicLinkApi
import com.qareplus.betterauth.api.MultiSessionApi
import com.qareplus.betterauth.api.OneTimeTokenApi
import com.qareplus.betterauth.api.OrganizationApi
import com.qareplus.betterauth.api.PasskeyApi
import com.qareplus.betterauth.api.PhoneNumberApi
import com.qareplus.betterauth.api.SignInApi
import com.qareplus.betterauth.api.SignUpApi
import com.qareplus.betterauth.api.TwoFactorApi
import com.qareplus.betterauth.api.asObject
import com.qareplus.betterauth.api.call
import com.qareplus.betterauth.api.get
import com.qareplus.betterauth.api.getForResult
import com.qareplus.betterauth.api.json
import com.qareplus.betterauth.api.jsonOrRedirect
import com.qareplus.betterauth.api.post
import com.qareplus.betterauth.api.postForResult
import com.qareplus.betterauth.api.toAuthResponse
import com.qareplus.betterauth.api.toSessionList
import com.qareplus.betterauth.api.toSession
import com.qareplus.betterauth.api.toJson
import com.qareplus.betterauth.api.toUser
import com.qareplus.betterauth.config.BetterAuthConfig
import com.qareplus.betterauth.http.Transport
import com.qareplus.betterauth.model.AccessTokenResult
import com.qareplus.betterauth.model.Account
import com.qareplus.betterauth.model.AccountInfo
import com.qareplus.betterauth.model.AuthResponse
import com.qareplus.betterauth.model.LinkSocialResult
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.RedirectResponse
import com.qareplus.betterauth.model.Session
import com.qareplus.betterauth.model.SessionData
import com.qareplus.betterauth.model.SignOutResult
import com.qareplus.betterauth.model.SocialSignInOutcome
import com.qareplus.betterauth.model.VerifyEmailResult
import com.qareplus.betterauth.model.bool
import com.qareplus.betterauth.model.jsonBody
import com.qareplus.betterauth.model.obj
import com.qareplus.betterauth.model.str
import com.qareplus.betterauth.result.BetterAuthError
import com.qareplus.betterauth.result.BetterAuthResult
import com.qareplus.betterauth.session.InMemorySessionStore
import com.qareplus.betterauth.session.StoredSession
import io.ktor.http.HttpMethod
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Entry point of the SDK.
 *
 * ```kotlin
 * val auth = BetterAuthClient {
 *     baseUrl = "https://api.example.com/api/auth"
 *     origin = "myapp://"          // must be one of the server's trustedOrigins
 * }
 * when (val result = auth.signIn.email("a@b.co", "secret")) {
 *     is BetterAuthResult.Success -> ...
 *     is BetterAuthResult.Failure -> ...
 * }
 * ```
 *
 * All calls are `suspend` and never throw for API or network failures; they return a [BetterAuthResult].
 * The client is thread-safe. Call [close] when you are done with it.
 */
public class BetterAuthClient internal constructor(
    internal val transport: Transport,
) : AutoCloseable {

    public val signUp: SignUpApi = SignUpApi(transport)
    public val signIn: SignInApi = SignInApi(transport)
    public val twoFactor: TwoFactorApi = TwoFactorApi(transport)
    public val emailOtp: EmailOtpApi = EmailOtpApi(transport)
    public val phoneNumber: PhoneNumberApi = PhoneNumberApi(transport)
    public val magicLink: MagicLinkApi = MagicLinkApi(transport)
    public val anonymous: AnonymousApi = AnonymousApi(transport)
    public val oneTimeToken: OneTimeTokenApi = OneTimeTokenApi(transport)
    public val multiSession: MultiSessionApi = MultiSessionApi(transport)
    public val jwt: JwtApi = JwtApi(transport)
    public val passkey: PasskeyApi = PasskeyApi(transport)
    public val admin: AdminApi = AdminApi(transport)
    public val organization: OrganizationApi = OrganizationApi(transport)

    // --- session ------------------------------------------------------------------------------------------------

    /**
     * Fetches the current session. A successful `null` means "not signed in" (the server answered `null`);
     * that is not an error.
     *
     * @param disableCookieCache Skip the server's cookie cache and read the database.
     * @param disableRefresh Do not extend the session's expiry.
     */
    public suspend fun getSession(
        disableCookieCache: Boolean = false,
        disableRefresh: Boolean = false,
    ): BetterAuthResult<SessionData?> = transport.get(
        "/get-session",
        query = mapOf(
            "disableCookieCache" to if (disableCookieCache) "true" else null,
            "disableRefresh" to if (disableRefresh) "true" else null,
        ),
    ) { it.toSessionDataOrNull() }

    /** Signs out on the server and forgets the local session. */
    public suspend fun signOut(): BetterAuthResult<SignOutResult> {
        val result = transport.post("/sign-out") { element ->
            val o = element.asObject()
            SignOutResult(o.bool("success") ?: true, o.str("url"), o.bool("redirect") ?: false)
        }
        // The user asked to leave: drop local credentials unless the server was unreachable.
        val unauthorized = (result.errorOrNull() as? BetterAuthError.Api)?.status == 401
        // Keep unrelated cookies (e.g. the 2FA trust_device marker); the server expires what it wants gone.
        if (result.isSuccess || unauthorized) transport.clearSessionCredentials()
        return result
    }

    /** Updates session fields. [fields] is merged into the request body as-is. */
    public suspend fun updateSession(fields: JsonObject): BetterAuthResult<Session?> =
        transport.post("/update-session", fields) { it.asObject().obj("session")?.toSession() }

    public suspend fun listSessions(): BetterAuthResult<List<Session>> =
        transport.get("/list-sessions") { it.toSessionList() }

    public suspend fun revokeSession(token: String): BetterAuthResult<OperationResult> =
        transport.postForResult("/revoke-session", jsonBody { put("token", token) })

    public suspend fun revokeSessions(): BetterAuthResult<OperationResult> = transport.postForResult("/revoke-sessions")

    public suspend fun revokeOtherSessions(): BetterAuthResult<OperationResult> =
        transport.postForResult("/revoke-other-sessions")

    // --- user ---------------------------------------------------------------------------------------------------

    /**
     * Updates the signed-in user. Standard fields are [name] and [image]; put anything from your server's
     * `user.additionalFields` in [additionalFields].
     */
    public suspend fun updateUser(
        name: String? = null,
        image: String? = null,
        additionalFields: JsonObject? = null,
    ): BetterAuthResult<OperationResult> = transport.postForResult(
        "/update-user",
        jsonBody {
            putAll(additionalFields)
            put("name", name)
            put("image", image)
        },
    )

    public suspend fun changeEmail(newEmail: String, callbackUrl: String? = null): BetterAuthResult<OperationResult> =
        transport.postForResult(
            "/change-email",
            jsonBody {
                put("newEmail", newEmail)
                put("callbackURL", callbackUrl)
            },
        )

    /** Changes the password. With [revokeOtherSessions] the server issues a fresh token, which the SDK stores. */
    public suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
        revokeOtherSessions: Boolean? = null,
    ): BetterAuthResult<AuthResponse> = transport.post(
        "/change-password",
        jsonBody {
            put("currentPassword", currentPassword)
            put("newPassword", newPassword)
            put("revokeOtherSessions", revokeOtherSessions)
        },
    ) { it.toAuthResponse() }

    /** Deletes the signed-in user. Depending on server config you must pass [password], a [token], or neither. */
    public suspend fun deleteUser(
        password: String? = null,
        token: String? = null,
        callbackUrl: String? = null,
    ): BetterAuthResult<OperationResult> {
        val result = transport.postForResult(
            "/delete-user",
            jsonBody {
                put("password", password)
                put("token", token)
                put("callbackURL", callbackUrl)
            },
        )
        if (result is BetterAuthResult.Success && result.value.success && result.value.message == "User deleted") {
            clearLocalSession()
        }
        return result
    }

    // --- email verification and password reset -------------------------------------------------------------------

    public suspend fun sendVerificationEmail(email: String, callbackUrl: String? = null): BetterAuthResult<OperationResult> =
        transport.postForResult(
            "/send-verification-email",
            jsonBody {
                put("email", email)
                put("callbackURL", callbackUrl)
            },
        )

    /** Verifies an email token from a link. With [callbackUrl] the server redirects; see [VerifyEmailResult.redirect]. */
    public suspend fun verifyEmail(token: String, callbackUrl: String? = null): BetterAuthResult<VerifyEmailResult> =
        transport.jsonOrRedirect(
            HttpMethod.Get,
            "/verify-email",
            query = mapOf("token" to token, "callbackURL" to callbackUrl),
            onRedirect = { VerifyEmailResult(success = true, redirect = it) },
        ) { element ->
            val o = element.asObject()
            VerifyEmailResult(success = o.bool("status") ?: true, user = o.obj("user")?.toUser())
        }

    /** Emails a password reset link. [redirectTo] is where the link sends the user (your app or web page). */
    public suspend fun requestPasswordReset(email: String, redirectTo: String? = null): BetterAuthResult<OperationResult> =
        transport.postForResult(
            "/request-password-reset",
            jsonBody {
                put("email", email)
                put("redirectTo", redirectTo)
            },
        )

    /** Sets a new password using the token from the reset link. */
    public suspend fun resetPassword(newPassword: String, token: String? = null): BetterAuthResult<OperationResult> =
        transport.postForResult(
            "/reset-password",
            jsonBody {
                put("newPassword", newPassword)
                put("token", token)
            },
        )

    /** Checks the signed-in user's password. A wrong password comes back as an [BetterAuthError.Api] failure. */
    public suspend fun verifyPassword(password: String): BetterAuthResult<OperationResult> =
        transport.postForResult("/verify-password", jsonBody { put("password", password) })

    /**
     * The server's reset-link endpoint. It validates [token] and answers with a redirect to
     * `callbackUrl?token=...`; the redirect is returned, not followed.
     */
    public suspend fun resetPasswordCallback(token: String, callbackUrl: String): BetterAuthResult<RedirectResponse> =
        transport.jsonOrRedirect(
            HttpMethod.Get,
            "/reset-password/$token",
            query = mapOf("callbackURL" to callbackUrl),
            onRedirect = { it },
        ) { RedirectResponse(200, null) }

    // --- accounts -----------------------------------------------------------------------------------------------

    public suspend fun listAccounts(): BetterAuthResult<List<Account>> =
        transport.call(HttpMethod.Get, "/list-accounts", ListSerializer(Account.serializer()))

    /**
     * Links a social account to the signed-in user. Without [idToken] the result carries a URL to open;
     * with one the account is linked immediately.
     */
    public suspend fun linkSocial(
        provider: String,
        callbackUrl: String? = null,
        errorCallbackUrl: String? = null,
        idToken: IdTokenCredentials? = null,
        scopes: List<String>? = null,
        requestSignUp: Boolean? = null,
        disableRedirect: Boolean? = null,
        additionalData: JsonObject? = null,
    ): BetterAuthResult<LinkSocialResult> = transport.post(
        "/link-social",
        jsonBody {
            put("provider", provider)
            put("callbackURL", callbackUrl)
            put("errorCallbackURL", errorCallbackUrl)
            put("idToken", idToken?.toJson())
            putStrings("scopes", scopes)
            put("requestSignUp", requestSignUp)
            put("disableRedirect", disableRedirect)
            put("additionalData", additionalData)
        },
    ) { element ->
        val o = element.asObject()
        LinkSocialResult(o.str("url"), o.bool("redirect") ?: false, o.bool("status"))
    }

    public suspend fun unlinkAccount(accountId: String): BetterAuthResult<OperationResult> =
        transport.postForResult("/unlink-account", jsonBody { put("accountId", accountId) })

    /**
     * Returns a valid provider access token for a linked account, refreshing it if needed.
     * Identify the account by [accountId], or set [useAccountCookie] when the server stores account cookies.
     */
    public suspend fun getAccessToken(
        accountId: String? = null,
        useAccountCookie: Boolean = false,
        userId: String? = null,
    ): BetterAuthResult<AccessTokenResult> = transport.call(
        HttpMethod.Post,
        "/get-access-token",
        AccessTokenResult.serializer(),
        body = accountSelection(accountId, useAccountCookie, userId),
    )

    /** Forces a refresh of a linked account's provider tokens. */
    public suspend fun refreshToken(
        accountId: String? = null,
        useAccountCookie: Boolean = false,
        userId: String? = null,
    ): BetterAuthResult<AccessTokenResult> = transport.call(
        HttpMethod.Post,
        "/refresh-token",
        AccessTokenResult.serializer(),
        body = accountSelection(accountId, useAccountCookie, userId),
    )

    public suspend fun accountInfo(accountId: String? = null): BetterAuthResult<AccountInfo> =
        transport.call(HttpMethod.Get, "/account-info", AccountInfo.serializer(), query = mapOf("accountId" to accountId))

    /** Health check (`GET /ok`). */
    public suspend fun ok(): BetterAuthResult<Boolean> = transport.get("/ok") { it.asObject().bool("ok") ?: true }

    // --- local session ------------------------------------------------------------------------------------------

    /** The session token the SDK would send as a bearer credential, if any. */
    public suspend fun currentToken(): String? = transport.store.get().token

    /** Snapshot of everything the SDK remembers (token, JWT, cookies). */
    public suspend fun storedSession(): StoredSession = transport.store.get()

    /** Restores a previously saved session, e.g. from your own secure storage. Does not talk to the server. */
    public suspend fun restoreSession(session: StoredSession) {
        transport.store.set(session)
    }

    /** Restores just a bearer token. */
    public suspend fun restoreToken(token: String) {
        val current = transport.store.get()
        transport.store.set(current.copy(token = token))
    }

    /**
     * The URL to open in a browser (Custom Tabs) for a redirect-based social sign-in or account link. It goes
     * through the server's `/expo-authorization-proxy`, which plants the OAuth state cookie in the browser.
     * [authorizationUrl] is the `url` from [SocialSignInOutcome.RedirectRequired] or [LinkSocialResult].
     * Requires the `expo()` plugin on the server.
     */
    public suspend fun authorizationProxyUrl(authorizationUrl: String): String =
        transport.authorizationProxyUrl(authorizationUrl, transport.oauthState())

    /**
     * Finishes a browser sign-in. Pass the `cookie` query parameter of the deep link the server redirected to;
     * the session it carries is stored. Follow with [getSession] to load the user.
     */
    public suspend fun completeBrowserSignIn(cookieParameter: String) {
        transport.importSetCookies(CookieJarAccess.split(cookieParameter))
    }

    /** Forgets the entire local session, cookies included, without calling the server. */
    public suspend fun clearLocalSession() {
        transport.store.clear()
    }

    /**
     * A view of this client that authenticates as the holder of [token], with its own isolated session and cookie
     * jar. It shares this client's connection pool. Use it on backends that validate many users' tokens:
     *
     * ```kotlin
     * val session = auth.withSession(bearerFromRequest).getSession()
     * ```
     *
     * Closing the returned client does not close the parent.
     */
    public fun withSession(token: String): BetterAuthClient =
        BetterAuthClient(transport.scoped(InMemorySessionStore(StoredSession(token = token))))

    // --- escape hatch -------------------------------------------------------------------------------------------

    /**
     * Calls any Better Auth endpoint, including ones from plugins this SDK does not wrap yet.
     * [method] is `"GET"` or `"POST"`; [path] is relative to `baseUrl` (e.g. `"/passkey/list-user-passkeys"`).
     * For GET requests put parameters in [query]; for POST requests put them in [body].
     */
    public suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): BetterAuthResult<JsonElement> =
        transport.json(HttpMethod.parse(method.uppercase()), path, query, body) { it }

    /** Like [request], decoding the response with [deserializer]. */
    public suspend fun <T> request(
        method: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): BetterAuthResult<T> =
        transport.call(HttpMethod.parse(method.uppercase()), path, deserializer, query, body)

    override fun close() {
        transport.close()
    }

    public companion object {
        /** Java-friendly factory: `BetterAuthClient.create(config)`. */
        @JvmStatic
        public fun create(config: BetterAuthConfig): BetterAuthClient = BetterAuthClient(Transport.create(config))
    }
}

/** `BetterAuthClient { baseUrl = "..." }` */
public fun BetterAuthClient(configure: BetterAuthConfig.() -> Unit): BetterAuthClient =
    BetterAuthClient.create(BetterAuthConfig().apply(configure))

/** Like [BetterAuthClient.request], decoding into [T]. */
public suspend inline fun <reified T> BetterAuthClient.request(
    method: String,
    path: String,
    body: JsonElement? = null,
    query: Map<String, String?> = emptyMap(),
): BetterAuthResult<T> = request(method, path, serializer<T>(), body, query)

private fun accountSelection(accountId: String?, useAccountCookie: Boolean, userId: String?): JsonObject = jsonBody {
    if (useAccountCookie) put("useAccountCookie", true) else put("accountId", accountId)
    put("userId", userId)
}

private fun JsonElement.toSessionDataOrNull(): SessionData? {
    if (this is JsonNull) return null
    val o = asObject()
    val session = o.obj("session") ?: return null
    val user = o.obj("user") ?: return null
    return SessionData(session.toSession(), user.toUser())
}

/** Bridges the internal cookie jar to the public client without exposing it. */
internal object CookieJarAccess {
    fun split(header: String): List<String> = com.qareplus.betterauth.http.CookieJar.splitSetCookieHeader(header)
}
