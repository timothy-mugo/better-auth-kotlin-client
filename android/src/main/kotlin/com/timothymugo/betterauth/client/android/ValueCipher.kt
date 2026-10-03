package com.timothymugo.betterauth.client.android

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the bytes [EncryptedDataStoreStorage] writes to disk. */
public interface ValueCipher {
    public fun encrypt(plain: ByteArray): ByteArray

    /** Must throw if [encrypted] was not produced by this cipher's current key. */
    public fun decrypt(encrypted: ByteArray): ByteArray
}

/**
 * AES-256-GCM. [keyProvider] supplies the key on every call, so the key can live in the Android Keystore
 * ([AndroidKeystoreCipher]) or be a plain key in unit tests. The cipher picks a fresh IV per message (Keystore keys
 * require that) and stores it in front of the ciphertext: `iv(12) + ciphertext + tag(16)`.
 */
public open class AesGcmValueCipher(private val keyProvider: () -> SecretKey) : ValueCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        if (encrypted.size <= IV_BYTES) throw GeneralSecurityException("Ciphertext too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, encrypted, 0, IV_BYTES))
        return cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
