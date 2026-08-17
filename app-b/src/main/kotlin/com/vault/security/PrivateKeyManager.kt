package com.vault.security

import android.content.Context
import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.crypto.KeyPayloadSerializer
import java.security.PrivateKey
import java.util.Arrays

/**
 * 私钥生命周期管理器 (核心安全组件)。
 *
 * 安全约束:
 * - 私钥明文仅在签名运算期间临时解密并驻留内存, 运算完成后立即用零字节覆写。
 * - 私钥落盘形态始终为 Keystore 加密密文, 永不存明文, 永不展示。
 *
 * 默认活动密钥 = 最近导入的密钥; [signChallenge] / [retrievePrivateKeyForSigning]
 * 的无参重载使用该默认密钥。
 *
 * 密钥策略: 导入即替换。Engine 与 Vault 为 1:1 绑定关系, 导入新密钥前会
 * 删除所有旧密钥, 保证保险箱内始终只有一把活动私钥。
 */
class PrivateKeyManager(context: Context) {

    private val keystoreManager = KeystoreManager()
    private val secureStorage = SecureStorage(context.applicationContext)
    private val ecdsa = EcdsaOperations()

    data class ImportResult(val fingerprint: String, val importedAt: Long)

    /**
     * 解码后的私钥与其 PKCS#8 明文编码 (后者用于运算后零字节覆写)。
     */
    private data class DecodedKey(
        val privateKey: PrivateKey,
        val encodedBytes: ByteArray
    )

    /**
     * 导入密钥: 校验 -> 删除旧密钥 -> 计算指纹 -> Keystore 加密 -> 落盘 -> 返回指纹。
     *
     * 单活动密钥模型: 重新绑定场景下, 旧密钥在此处被彻底移除,
     * 避免保险箱残留已失效的旧身份密钥。
     */
    fun importKey(keyData: KeyPayloadSerializer.KeyPairData): ImportResult {
        // 1. 校验密钥对合法性 (曲线 + 公私钥匹配)
        if (!KeyPayloadSerializer.validate(keyData)) {
            throw IllegalArgumentException("Invalid key pair: validation failed")
        }

        val publicKey = ecdsa.decodePublicKey(keyData.publicKey)
        val privateKey = ecdsa.decodePrivateKey(keyData.privateKey)

        // 2. 计算公钥指纹 (节点 ID)
        val fingerprint = KeyFingerprint.compute(publicKey)

        // 3. 删除所有旧密钥 (导入即替换, 防止旧身份残留)
        secureStorage.clearAllKeys()

        // 4. Keystore AES-256-GCM 加密私钥明文 (PKCS#8 编码)
        val privateKeyEncoded = ecdsa.encodePrivateKey(privateKey)
        val encrypted = keystoreManager.encrypt(privateKeyEncoded)

        // 5. 落盘
        val now = System.currentTimeMillis()
        secureStorage.storePrivateKey(
            fingerprint = fingerprint,
            privateKeyCipher = encrypted.ciphertext,
            iv = encrypted.iv,
            publicKey = keyData.publicKey,
            importedAt = now
        )

        // 6. 立即零字节覆写内存中的私钥明文编码
        zeroMemory(privateKeyEncoded)

        return ImportResult(fingerprint, now)
    }

    /**
     * 解密并以 PrivateKey 形式返回活动密钥 (最近导入)。
     * 解码后立即覆写解密得到的明文字节。
     */
    fun retrievePrivateKeyForSigning(): PrivateKey {
        val fingerprint = secureStorage.latestFingerprint()
            ?: throw IllegalStateException("No stored key in vault")
        return retrievePrivateKeyForSigning(fingerprint)
    }

    /**
     * 解密指定指纹的私钥。
     */
    fun retrievePrivateKeyForSigning(fingerprint: String): PrivateKey {
        val decoded = decodeForSigning(fingerprint)
        // 解码完成后立即覆写解密明文 (PrivateKey 已持有自己的密钥副本)
        zeroMemory(decoded.encodedBytes)
        return decoded.privateKey
    }

    /**
     * 对挑战值签名 (使用活动密钥), 签名后立即零字节覆写私钥明文内存。
     */
    fun signChallenge(challenge: ByteArray): ByteArray {
        val fingerprint = secureStorage.latestFingerprint()
            ?: throw IllegalStateException("No stored key in vault")
        return signChallenge(fingerprint, challenge)
    }

    /**
     * 对挑战值签名 (指定指纹), 签名后立即零字节覆写私钥明文内存。
     */
    fun signChallenge(fingerprint: String, challenge: ByteArray): ByteArray {
        val decoded = decodeForSigning(fingerprint)
        return try {
            val signature = ecdsa.sign(decoded.privateKey, challenge)
            // 尽力覆写 PrivateKey 暴露的编码副本 (Java 安全 API 限制下尽力而为)
            runCatching { decoded.privateKey.encoded }.getOrNull()?.let { zeroMemory(it) }
            signature
        } finally {
            // 签名完成后立即零字节覆写解密后的私钥明文
            zeroMemory(decoded.encodedBytes)
        }
    }

    fun hasStoredKey(): Boolean = secureStorage.hasStoredKey()

    /**
     * 用零字节覆写缓冲区, 用于敏感内存清理。
     */
    fun zeroMemory(buffer: ByteArray) {
        Arrays.fill(buffer, 0.toByte())
    }

    private fun decodeForSigning(fingerprint: String): DecodedKey {
        val (cipher, iv) = secureStorage.retrieveEncryptedPrivateKey(fingerprint)
            ?: throw IllegalStateException("No stored key for fingerprint: $fingerprint")
        // Keystore 解密得到 PKCS#8 明文
        val plaintext = keystoreManager.decrypt(cipher, iv)
        val privateKey = ecdsa.decodePrivateKey(plaintext)
        return DecodedKey(privateKey, plaintext)
    }
}
