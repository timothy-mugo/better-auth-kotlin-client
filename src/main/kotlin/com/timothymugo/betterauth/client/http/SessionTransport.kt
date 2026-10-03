package com.timothymugo.betterauth.client.http

import com.timothymugo.betterauth.client.session.StoredCookie
import com.timothymugo.betterauth.client.session.StoredSession
import io.ktor.http.fromHttpToGmtDate

/** Which credential the SDK presents to the server. */
public enum class AuthMode {
    /** `Authorization: Bearer <token>` (needs the `bearer()` plugin on the server). Non-session cookies are still replayed. */
    Bearer,

    /** Replays every cookie the server set, like a browser. */
    Cookie,
}

/** Response headers, lower-cased by name. */
public class ResponseHeaders(entries: Map<String, List<String>>) {
    private val map: Map<String, List<String>> = entries.mapKeys { it.key.lowercase() }

    public fun getAll(name: String): List<String> = map[name.lowercase()].orEmpty()

    public fun get(name: String): String? = getAll(name).firstOrNull()
}

/**
 * Decides how a session is attached to requests and how it is learned from responses.
 * Two implementations are provided ([BearerSessionTransport], [CookieSessionTransport]); implement this
 * interface to plug in something else.
 */
public interface SessionTransport {
    /** Headers to add to an outgoing request. */
    public fun outgoingHeaders(state: StoredSession, cookiePrefix: String, nowMillis: Long): Map<String, String>

    /** Returns the state after observing a response. */
    public fun applyResponse(
        state: StoredSession,
        headers: ResponseHeaders,
        cookiePrefix: String,
        nowMillis: Long,
    ): StoredSession
}

/** Sends the token as a bearer credential, keeps every other cookie (2FA, passkey challenge, multi-session...). */
public object BearerSessionTransport : SessionTransport {
    override fun outgoingHeaders(state: StoredSession, cookiePrefix: String, nowMillis: Long): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        state.token?.let { headers["Authorization"] = "Bearer $it" }
        CookieJar.header(state, nowMillis) { !CookieJar.isSessionCredential(it, cookiePrefix) }
            ?.let { headers["Cookie"] = it }
        return headers
    }

    override fun applyResponse(
        state: StoredSession,
        headers: ResponseHeaders,
        cookiePrefix: String,
        nowMillis: Long,
    ): StoredSession = CookieJar.apply(state, headers, cookiePrefix, nowMillis)
}

/** Replays the full cookie jar, including the session cookie. */
public object CookieSessionTransport : SessionTransport {
    override fun outgoingHeaders(state: StoredSession, cookiePrefix: String, nowMillis: Long): Map<String, String> {
        val cookie = CookieJar.header(state, nowMillis) { true } ?: return emptyMap()
        return mapOf("Cookie" to cookie)
    }

    override fun applyResponse(
        state: StoredSession,
        headers: ResponseHeaders,
        cookiePrefix: String,
        nowMillis: Long,
    ): StoredSession = CookieJar.apply(state, headers, cookiePrefix, nowMillis)
}

/** Minimal RFC 6265 jar: parses `Set-Cookie`, honours `Max-Age`/`Expires`, and learns `set-auth-token`. */
internal object CookieJar {
    private val securePrefixes = listOf("__Secure-", "__Host-")

    private fun baseName(name: String): String =
        securePrefixes.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) } ?: name

    /** `true` for cookies named `<prefix>...` (ignoring `__Secure-`/`__Host-`); a blank prefix accepts everything. */
    fun isBetterAuthCookie(name: String, prefix: String): Boolean = prefix.isBlank() || baseName(name).startsWith(prefix)

    /** The session token cookie (`<prefix>.session_token`), with or without a `__Secure-` prefix. */
    fun isSessionTokenCookie(name: String, prefix: String): Boolean = baseName(name) == "$prefix.session_token"

    /** Cookies that carry or cache the session itself; bearer mode drops them so the token is the single source of truth. */
    fun isSessionCredential(name: String, prefix: String): Boolean {
        val base = baseName(name)
        return base == "$prefix.session_token" || base == "$prefix.session_data"
    }

    /** What the server's `/sign-out` expires, minus anything the SDK should keep (`trust_device`, last login method). */
    fun isClearedOnSignOut(name: String, prefix: String): Boolean {
        val base = baseName(name)
        return isSessionCredential(name, prefix) ||
            base == "$prefix.account_data" || base.startsWith("$prefix.account_data.") ||
            base == "$prefix.dont_remember" || base == "$prefix.oauth_state"
    }

    fun clearSession(state: StoredSession, prefix: String): StoredSession = StoredSession(
        token = null,
        jwt = null,
        cookies = state.cookies.filterKeys { !isClearedOnSignOut(it, prefix) },
    )

    /** The value of the pending OAuth `oauth_state` cookie, URI-decoded, if the jar has one. */
    fun oauthState(state: StoredSession, prefix: String): String? =
        state.cookies.values.firstOrNull { baseName(it.name) == "$prefix.oauth_state" }?.let { decode(it.value) }

    /**
     * Splits a folded `Set-Cookie` header (several cookies joined by commas, as Better Auth puts in the `cookie`
     * query parameter of a deep link) without breaking on the comma inside `Expires=Wed, 21 Oct ...`.
     */
    fun splitSetCookieHeader(header: String): List<String> =
        header.split(Regex(",\\s*(?=[A-Za-z0-9_.!%\\-]+=)")).map { it.trim() }.filter { it.isNotEmpty() }

    fun header(state: StoredSession, nowMillis: Long, include: (String) -> Boolean): String? =
        state.cookies.values
            .filter { include(it.name) && !it.isExpired(nowMillis) }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifEmpty { null }

    fun apply(state: StoredSession, headers: ResponseHeaders, cookiePrefix: String, nowMillis: Long): StoredSession {
        var token = state.token
        var jwt = state.jwt
        val cookies = LinkedHashMap(state.cookies)

        for (line in headers.getAll("set-cookie")) {
            val cookie = parseSetCookie(line, nowMillis) ?: continue
            // Like the Expo plugin: ignore cookies that are not Better Auth's (third-party, CDN, load balancer, ...).
            if (!isBetterAuthCookie(cookie.name, cookiePrefix)) continue
            val deleted = cookie.value.isEmpty() || cookie.isExpired(nowMillis)
            if (deleted) {
                cookies.remove(cookie.name)
                if (isSessionTokenCookie(cookie.name, cookiePrefix)) token = null
            } else {
                cookies[cookie.name] = cookie
                if (isSessionTokenCookie(cookie.name, cookiePrefix)) token = decode(cookie.value)
            }
        }

        // Authoritative when present: the bearer plugin emits this whenever it sets the session cookie.
        headers.get("set-auth-token")?.takeIf { it.isNotBlank() }?.let { token = it }
        headers.get("set-auth-jwt")?.takeIf { it.isNotBlank() }?.let { jwt = it }

        return StoredSession(token = token, jwt = jwt, cookies = cookies)
    }

    private fun StoredCookie.isExpired(nowMillis: Long): Boolean =
        expiresAtEpochMillis?.let { it <= nowMillis } ?: false

    internal fun parseSetCookie(line: String, nowMillis: Long): StoredCookie? {
        val parts = line.split(';')
        val pair = parts.first()
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()

        var maxAge: Long? = null
        var expires: Long? = null
        for (attribute in parts.drop(1)) {
            val kv = attribute.split('=', limit = 2)
            when (kv[0].trim().lowercase()) {
                "max-age" -> maxAge = kv.getOrNull(1)?.trim()?.toLongOrNull()
                "expires" -> expires = kv.getOrNull(1)?.trim()?.let {
                    runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull()
                }
            }
        }
        // Max-Age wins over Expires (RFC 6265 section 5.3).
        val expiresAt = maxAge?.let { nowMillis + it * 1000 } ?: expires
        return StoredCookie(name, value, expiresAt)
    }

    fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)
}
