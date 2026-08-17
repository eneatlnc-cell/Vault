package com.vault.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeystore (TEE) 密钥管理器。
 *
 * 职责:
 * - 在 AndroidKeyStore 中生成 AES-256-GCM 密钥 (硬件信任根)。
 * - 提供 [encrypt] / [decrypt] 对称加解密原语, 供私钥密文落盘使用。
 *
 * 安全特性:
 * - 密钥永不离开 Keystore (TEE/StrongBox), 仅返回密钥句柄用于加解密。
 * - BLOCK_MODE = GCM, ENCRYPTION_PADDING = NO_PADDING, KeySize = 256。
 * - GCM 模式下 [Cipher.doFinal] 输出 "密文 + AuthTag" 拼接, 解密时整体回灌。
 */
class KeystoreManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "vault_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128 // 16 bytes
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * 加密结果: ciphertext 已包含 GCM AuthTag。
     */
    data class EncryptedData(
        val ciphertext: ByteArray, // 密文 + AuthTag 拼接
        val iv: ByteArray           // 12 bytes GCM IV
    ) {
        fun ciphertextBase64(): String = Base64.getEncoder().encodeToString(ciphertext)
        fun ivBase64(): String = Base64.getEncoder().encodeToString(iv)
    }

    /**
     * 获取或创建 AndroidKeyStore 中的 AES-256 密钥。
     */
    fun getOrCreateKey(keyAlias: String = DEFAULT_KEY_ALIAS): SecretKey {
        keyStore.getEntry(keyAlias, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * 使用 Keystore 密钥加密明文, 返回 (密文+AuthTag, IV)。
     */
    fun encrypt(plaintext: ByteArray, keyAlias: String = DEFAULT_KEY_ALIAS): EncryptedData {
        val secretKey = getOrCreateKey(keyAlias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val cipherOutput = cipher.doFinal(plaintext) // 密文 + AuthTag
        val iv = cipher.iv
        return EncryptedData(cipherOutput, iv)
    }

    /**
     * 使用 Keystore 密钥解密密文 (需回灌同一 IV)。
     *
     * @param ciphertext 密文 + AuthTag 拼接 (来自 [EncryptedData.ciphertext])
     * @param iv         加密时生成的 12 bytes IV
     */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray, keyAlias: String = DEFAULT_KEY_ALIAS): ByteArray {
        val secretKey = getOrCreateKey(keyAlias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
