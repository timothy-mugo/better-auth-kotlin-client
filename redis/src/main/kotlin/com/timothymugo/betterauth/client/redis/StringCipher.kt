package com.timothymugo.betterauth.client.redis

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts values before [RedisStorage] writes them, so session tokens are not readable in Redis. */
public interface StringCipher {
    public fun encrypt(plain: String): String

    /** Must throw if [encoded] was not produced by this cipher with the same key. */
    public fun decrypt(encoded: String): String
}

/**
 * AES-GCM using only the JDK. [key] must be 16, 24 or 32 bytes (use 32). Each value gets a random IV; the output is
 * `v1:` + Base64(iv + ciphertext + tag). Keep the key in your secret manager, never in Redis.
 */
public class AesGcmStringCipher(key: ByteArray) : StringCipher {
    private val secretKey = SecretKeySpec(key, "AES").also {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes, was ${key.size}" }
    }
    private val random = SecureRandom()

    override fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return VERSION + Base64.getEncoder().encodeToString(iv + encrypted)
    }

    override fun decrypt(encoded: String): String {
        require(encoded.startsWith(VERSION)) { "Unknown encryption format" }
        val bytes = Base64.getDecoder().decode(encoded.removePrefix(VERSION))
        require(bytes.size > IV_BYTES) { "Ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
