package com.qareplus.betterauth.config

import com.qareplus.betterauth.http.AuthMode
import com.qareplus.betterauth.http.BearerSessionTransport
import com.qareplus.betterauth.http.CookieSessionTransport
import com.qareplus.betterauth.http.SessionTransport
import com.qareplus.betterauth.session.InMemorySessionStore
import com.qareplus.betterauth.session.SessionStore
import io.ktor.client.engine.HttpClientEngine

@DslMarker
public annotation class BetterAuthDsl

/** Configuration for [com.qareplus.betterauth.BetterAuthClient]. */
@BetterAuthDsl
public class BetterAuthConfig {
    /**
     * Full URL of the Better Auth handler *including its base path*, e.g. `https://api.example.com/api/auth`.
     * Endpoint paths are appended to this verbatim.
     */
    public var baseUrl: String = ""

    /** How the session is presented to the server. Defaults to [AuthMode.Bearer]. */
    public var mode: AuthMode = AuthMode.Bearer

    /** Override to supply your own [SessionTransport]; takes precedence over [mode]. */
    public var sessionTransport: SessionTransport? = null

    /** Where the session is remembered. Defaults to memory only. */
    public var sessionStore: SessionStore = InMemorySessionStore()

    /**
     * The server's `advanced.cookiePrefix` (Better Auth default `better-auth`). Used to recognise the
     * session cookie in `Set-Cookie`.
     */
    public var cookiePrefix: String = "better-auth"

    /**
     * Value for the `Origin` header. Better Auth rejects POSTs that carry cookies unless their origin is in
     * `trustedOrigins`, and the SDK's cookie jar (2FA, multi-session, last-login-method, ...) means most POSTs do.
     *
     * - `null` (default): use the origin of [baseUrl]. Better Auth always trusts its own base URL, so this works
     *   when [baseUrl] is the server's configured `baseURL`.
     * - a value (e.g. your app scheme `myapp://`): send that; it must be in the server's `trustedOrigins`.
     * - blank (`""`): send no `Origin` header.
     */
    public var origin: String? = null

    /** Value for the `expo-origin` header, for servers running the Expo plugin. */
    public var expoOrigin: String? = null

    /** Sent as `User-Agent`. */
    public var userAgent: String = "better-auth-kt/$SDK_VERSION"

    /** Extra headers added to every request (e.g. an API gateway key). */
    public val headers: MutableMap<String, String> = LinkedHashMap()

    /** HTTP engine. Defaults to OkHttp, which works on Android and on the JVM. */
    public var engine: HttpClientEngine? = null

    public var requestTimeoutMillis: Long = 30_000

    public var connectTimeoutMillis: Long = 10_000

    internal fun resolveTransport(): SessionTransport = sessionTransport ?: when (mode) {
        AuthMode.Bearer -> BearerSessionTransport
        AuthMode.Cookie -> CookieSessionTransport
    }

    internal fun validate() {
        require(baseUrl.isNotBlank()) { "BetterAuthConfig.baseUrl must be set" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "BetterAuthConfig.baseUrl must start with http:// or https://"
        }
    }

    internal companion object {
        const val SDK_VERSION = "0.1.0"
    }
}
