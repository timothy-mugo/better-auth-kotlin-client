package com.timothymugo.betterauth.client.config

import com.timothymugo.betterauth.client.http.AuthMode
import com.timothymugo.betterauth.client.http.BearerSessionTransport
import com.timothymugo.betterauth.client.http.CookieSessionTransport
import com.timothymugo.betterauth.client.http.SessionTransport
import com.timothymugo.betterauth.client.session.InMemorySessionStore
import com.timothymugo.betterauth.client.session.SessionStore
import com.timothymugo.betterauth.client.session.StorageSessionStore
import com.timothymugo.betterauth.client.storage.KeyValueStorage
import io.ktor.client.engine.HttpClientEngine

@DslMarker
public annotation class BetterAuthDsl

/** Configuration for [com.timothymugo.betterauth.client.BetterAuthClient]. */
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

    /**
     * Where the session (cookies, token) and the `get-session` cache are persisted. Like the Better Auth Expo
     * plugin's `storage` option, this is an adapter you pass in:
     * `EncryptedDataStoreStorage` on Android, `RedisStorage` on a backend. Defaults to memory only.
     */
    public var storage: KeyValueStorage? = null

    /** Prefix for the storage keys (`<prefix>_cookie`, `<prefix>_session_data`). */
    public var storagePrefix: String = StorageSessionStore.DEFAULT_PREFIX

    /** Skip caching the `get-session` result in [storage] (see `BetterAuthClient.cachedSession`). */
    public var disableCache: Boolean = false

    /** Full control over persistence. Takes precedence over [storage]. */
    public var sessionStore: SessionStore? = null

    /**
     * The server's `advanced.cookiePrefix` (Better Auth default `better-auth`). **Must match the server.**
     * Like the Expo plugin, the SDK only keeps cookies whose name starts with this prefix (ignoring `__Secure-`), so
     * third-party cookies are never stored. With a wrong prefix the session cookie, the two-factor challenge,
     * `trust_device` and the multi-session cookies are all silently dropped: bearer sign-in still works, but 2FA and
     * multi-session do not. A blank prefix keeps every cookie.
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
    public var userAgent: String = "better-auth-kt-client/$SDK_VERSION"

    /** Extra headers added to every request (e.g. an API gateway key). */
    public val headers: MutableMap<String, String> = LinkedHashMap()

    /** HTTP engine. Defaults to OkHttp, which works on Android and on the JVM. */
    public var engine: HttpClientEngine? = null

    public var requestTimeoutMillis: Long = 30_000

    public var connectTimeoutMillis: Long = 10_000

    internal fun resolveSessionStore(): SessionStore =
        sessionStore ?: storage?.let { StorageSessionStore(it, storagePrefix) } ?: InMemorySessionStore()

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
