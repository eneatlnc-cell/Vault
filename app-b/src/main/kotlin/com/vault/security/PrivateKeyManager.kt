package com.vault.security

import android.content.Context
import com.securesocial.core.crypto.EcdsaOperations
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.crypto.KeyPayloadSerializer
import com.securesocial.core.ipc.IpcContract
import java.security.PrivateKey
import java.util.Arrays

/**
 * 私钥生命周期管理器 (核心安全组件, v3: 多应用绑定)。
 *
 * 安全约束:
 * - 私钥明文仅在签名运算期间临时解密并驻留内存, 运算完成后立即用零字节覆写。
 * - 私钥落盘形态始终为 Keystore 加密密文, 永不存明文, 永不展示。
 *
 * v3 密钥策略: 每个接入应用一把活动密钥 (以来源应用包名路由):
 * - [importKey]: 校验 → 仅替换该应用的旧绑定 (其他应用不受影响) → 计算指纹
 *   → Keystore 加密 → 落盘 (含应用显示名, 供状态页区分) → 返回指纹。
 * - [signChallenge]: 按来源应用包名取对应绑定私钥签名。
 *
 * 默认应用 = Engine ([IpcContract.ENGINE_PACKAGE]); 无参重载兼容旧调用点
 * (动态码等 Vault 本地功能)。
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
     * 导入密钥 (v3: 绑定到来源应用)。
     *
     * @param keyData      Engine 生成的密钥对 (公私钥)
     * @param appPackage   来源应用包名 (URI app 参数, 经 signature 权限门禁)
     * @param appLabel     来源应用显示名 (PackageManager 解析, 状态页展示用)
     */
    fun importKey(
        keyData: KeyPayloadSerializer.KeyPairData,
        appPackage: String = IpcContract.ENGINE_PACKAGE,
        appLabel: String = appPackage
    ): ImportResult {
        // 1. 校验密钥对合法性 (曲线 + 公私钥匹配)
        if (!KeyPayloadSerializer.validate(keyData)) {
            throw IllegalArgumentException("Invalid key pair: validation failed")
        }

        val publicKey = ecdsa.decodePublicKey(keyData.publicKey)
        val privateKey = ecdsa.decodePrivateKey(keyData.privateKey)

        // 2. 计算公钥指纹 (节点 ID)
        val fingerprint = KeyFingerprint.compute(publicKey)

        // 3. 替换该应用的旧绑定 (v3: 其他应用的绑定保留)
        secureStorage.deleteBinding(appPackage)

        // 4. Keystore AES-256-GCM 加密私钥明文 (PKCS#8 编码)
        val privateKeyEncoded = ecdsa.encodePrivateKey(privateKey)
        val encrypted = keystoreManager.encrypt(privateKeyEncoded)

        // 5. 落盘 (v3: 含应用包名 + 显示名)
        val now = System.currentTimeMillis()
        secureStorage.storeBinding(
            appPackage = appPackage,
            appLabel = appLabel,
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
     * 对挑战值签名 (按来源应用路由绑定私钥), 签名后立即零字节覆写私钥明文内存。
     */
    fun signChallenge(challenge: ByteArray, appPackage: String): ByteArray {
        val decoded = decodeForSigning(appPackage)
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

    /**
     * 无参重载: 默认路由到 Engine 的绑定密钥 (Vault 本地功能用, 如动态码)。
     */
    fun signChallenge(challenge: ByteArray): ByteArray {
        val appPackage = secureStorage.latestAppPackage()
            ?: throw IllegalStateException("No stored key in vault")
        return signChallenge(challenge, appPackage)
    }

    /** 指定应用是否已绑定密钥 */
    fun hasStoredKey(appPackage: String = IpcContract.ENGINE_PACKAGE): Boolean {
        return if (appPackage == IpcContract.ENGINE_PACKAGE) {
            // Engine 查询兼容: 任意绑定存在即视为可用 (含迁移数据)
            secureStorage.hasStoredKey()
        } else {
            secureStorage.hasBinding(appPackage)
        }
    }

    /**
     * 用零字节覆写缓冲区, 用于敏感内存清理。
     */
    fun zeroMemory(buffer: ByteArray) {
        Arrays.fill(buffer, 0.toByte())
    }

    private fun decodeForSigning(appPackage: String): DecodedKey {
        val (cipher, iv) = secureStorage.retrieveEncryptedPrivateKey(appPackage)
            ?: throw IllegalStateException("No stored key for app: $appPackage")
        // Keystore 解密得到 PKCS#8 明文
        val plaintext = keystoreManager.decrypt(cipher, iv)
        val privateKey = ecdsa.decodePrivateKey(plaintext)
        return DecodedKey(privateKey, plaintext)
    }
}
