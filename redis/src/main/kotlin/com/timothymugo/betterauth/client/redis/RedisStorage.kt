package com.timothymugo.betterauth.client.redis

import com.timothymugo.betterauth.client.storage.KeyValueStorage
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import java.time.Duration
import kotlinx.coroutines.future.await

/**
 * [KeyValueStorage] on Redis or Valkey (both speak the same protocol), for backends. Pair it with a per-user prefix:
 *
 * ```kotlin
 * val storage = RedisStorage.connect("redis://localhost:6379")
 * val perUser = auth.withStorage(storage, prefix = "user-$userId")
 * ```
 *
 * Values are stored at `<keyPrefix><key>` with a TTL that is renewed every time the SDK writes. Pass a [cipher] to keep
 * session tokens unreadable inside Redis.
 *
 * The SDK serializes its own read-modify-write cycles inside one process only. Two processes writing the *same*
 * prefix at the same time can overwrite each other; give each end user to one writer at a time (sticky routing, or a
 * lock) if that can happen.
 *
 * @param commands Lettuce async commands: `connection.async()` from a standalone, Sentinel or Cluster connection.
 * @param keyPrefix Namespace for every key this storage writes.
 * @param ttl Expiry applied on each write; `null` keeps keys until removed.
 * @param cipher Optional encryption of values at rest.
 */
public class RedisStorage(
    private val commands: RedisClusterAsyncCommands<String, String>,
    private val keyPrefix: String = DEFAULT_KEY_PREFIX,
    private val ttl: Duration? = DEFAULT_TTL,
    private val cipher: StringCipher? = null,
    private val onClose: () -> Unit = {},
) : KeyValueStorage, AutoCloseable {

    override suspend fun getItem(key: String): String? {
        val stored = commands.get(keyPrefix + key).await() ?: return null
        val cipher = cipher ?: return stored
        // A value we cannot decrypt (rotated key, tampering, written without a cipher) is treated as missing.
        return runCatching { cipher.decrypt(stored) }.getOrNull()
    }

    override suspend fun setItem(key: String, value: String) {
        val stored = cipher?.encrypt(value) ?: value
        val args = ttl?.let { SetArgs.Builder.ex(it) }
        val redisKey = keyPrefix + key
        if (args == null) commands.set(redisKey, stored).await() else commands.set(redisKey, stored, args).await()
    }

    override suspend fun removeItem(key: String) {
        commands.del(keyPrefix + key).await()
    }

    /** Closes the connection if this storage opened it via [connect]; otherwise does nothing. */
    override fun close() {
        onClose()
    }

    public companion object {
        public const val DEFAULT_KEY_PREFIX: String = "better-auth:"
        public val DEFAULT_TTL: Duration = Duration.ofDays(30)

        /**
         * Connects to [uri] (`redis://[user:password@]host:port[/db]`, `rediss://` for TLS; works for Valkey too) and
         * owns the connection: call [close] when done.
         */
        public fun connect(
            uri: String,
            keyPrefix: String = DEFAULT_KEY_PREFIX,
            ttl: Duration? = DEFAULT_TTL,
            cipher: StringCipher? = null,
        ): RedisStorage {
            val client = RedisClient.create(RedisURI.create(uri))
            val connection: StatefulRedisConnection<String, String> = client.connect()
            return RedisStorage(connection.async(), keyPrefix, ttl, cipher) {
                connection.close()
                client.shutdown()
            }
        }
    }
}
