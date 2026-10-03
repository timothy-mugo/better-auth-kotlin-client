package com.timothymugo.betterauth.client.session

import com.timothymugo.betterauth.client.storage.KeyValueStorage
import kotlinx.serialization.json.Json

/**
 * A [SessionStore] on top of any [KeyValueStorage], laid out like the Better Auth Expo plugin:
 * the cookie jar (plus token and JWT) lives under `<prefix>_cookie` and the cached `get-session` result under
 * `<prefix>_session_data`.
 *
 * Use a different [prefix] per user when one storage serves many users (a backend with Redis, for example).
 * Unreadable or corrupt data is treated as "no session", never as an error.
 */
public class StorageSessionStore(
    private val storage: KeyValueStorage,
    prefix: String = DEFAULT_PREFIX,
) : SessionStore {
    private val cookieKey = "${prefix}_cookie"
    private val cacheKey = "${prefix}_session_data"

    override suspend fun get(): StoredSession {
        val raw = storage.getItem(cookieKey) ?: return StoredSession()
        return runCatching { json.decodeFromString(StoredSession.serializer(), raw) }.getOrDefault(StoredSession())
    }

    override suspend fun set(session: StoredSession) {
        if (session.isEmpty) storage.removeItem(cookieKey)
        else storage.setItem(cookieKey, json.encodeToString(StoredSession.serializer(), session))
    }

    override suspend fun clear() {
        storage.removeItem(cookieKey)
        storage.removeItem(cacheKey)
    }

    override suspend fun getSessionCache(): String? = storage.getItem(cacheKey)

    override suspend fun setSessionCache(json: String?) {
        if (json == null) storage.removeItem(cacheKey) else storage.setItem(cacheKey, json)
    }

    public companion object {
        public const val DEFAULT_PREFIX: String = "better-auth"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
