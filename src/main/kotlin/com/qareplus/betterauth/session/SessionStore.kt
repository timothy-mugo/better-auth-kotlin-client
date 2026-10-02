package com.qareplus.betterauth.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A cookie kept by the SDK's jar. Scoping attributes (domain, path, secure) are ignored: the client talks to one base URL. */
@Serializable
public data class StoredCookie(
    val name: String,
    /** The raw, still URI-encoded value exactly as the server sent it. */
    val value: String,
    /** Absolute expiry in epoch milliseconds, or `null` for a session cookie. */
    val expiresAtEpochMillis: Long? = null,
)

/**
 * Everything the SDK remembers about the current session. It is `@Serializable` so persistent stores
 * (EncryptedSharedPreferences, DataStore, a database row, ...) can save it as JSON.
 */
@Serializable
public data class StoredSession(
    /** Session token, as sent in `Authorization: Bearer <token>`. */
    val token: String? = null,
    /** Last JWT seen in the `set-auth-jwt` response header (JWT plugin), if any. */
    val jwt: String? = null,
    /** Cookie jar keyed by cookie name. */
    val cookies: Map<String, StoredCookie> = emptyMap(),
) {
    public val isEmpty: Boolean get() = token == null && jwt == null && cookies.isEmpty()
}

/**
 * Persistence for [StoredSession]. Implementations must be safe to call from any coroutine.
 * The SDK serializes its own read-modify-write cycles, so a plain get/set pair is enough.
 */
public interface SessionStore {
    public suspend fun get(): StoredSession

    public suspend fun set(session: StoredSession)

    public suspend fun clear() {
        set(StoredSession())
    }
}

/** Default store: keeps the session in memory only. */
public class InMemorySessionStore(initial: StoredSession = StoredSession()) : SessionStore {
    private val mutex = Mutex()
    private var current: StoredSession = initial

    override suspend fun get(): StoredSession = mutex.withLock { current }

    override suspend fun set(session: StoredSession) {
        mutex.withLock { current = session }
    }
}
