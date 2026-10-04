package com.timothymugo.betterauth.client.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the SDK keeps its session between calls and app launches. This is the Kotlin counterpart of the Better Auth
 * Expo plugin's `storage` option: a tiny key-value interface that the *app* supplies, so the SDK itself never depends
 * on Android or Redis.
 *
 * Implementations own their security (an Android adapter encrypts, a Redis adapter may encrypt) and their key
 * constraints. All methods must be safe to call from any coroutine.
 *
 * Available adapters (separate artifacts, add only the one you need):
 * - `better-auth-kotlin-client-android`: `EncryptedDataStoreStorage`
 * - `better-auth-kotlin-client-redis`: `RedisStorage` (Redis and Valkey)
 */
public interface KeyValueStorage {
    public suspend fun getItem(key: String): String?

    public suspend fun setItem(key: String, value: String)

    public suspend fun removeItem(key: String)
}

/** Keeps everything in memory. The default; nothing survives a restart. */
public class InMemoryStorage : KeyValueStorage {
    private val mutex = Mutex()
    private val items = HashMap<String, String>()

    override suspend fun getItem(key: String): String? = mutex.withLock { items[key] }

    override suspend fun setItem(key: String, value: String) {
        mutex.withLock { items[key] = value }
    }

    override suspend fun removeItem(key: String) {
        mutex.withLock { items.remove(key) }
    }
}
