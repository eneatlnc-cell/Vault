package com.vault.migration

import android.content.Context
import android.content.pm.PackageManager
import com.securesocial.core.crypto.KeyPayloadSerializer
import com.vault.security.KeystoreManager
import com.vault.security.PrivateKeyManager
import com.vault.security.SecureStorage

/**
 * 绑定迁移管理器 (v3.18.0)。
 *
 * 职责: 在 "旧 Vault ↔ 新 Vault" 之间经二维码光学通道转移应用绑定私钥。
 *
 * 安全模型 (与 Engine→Vault 密钥导入完全同构):
 * - 导出: UI 层先过 BIOMETRIC_STRONG 指纹门 → 本类解密私钥 → 序列化
 *   → 返回 JSON 字符串 → 内存中的私钥明文立即零字节覆写。
 *   私钥明文仅在指纹门后的数秒内驻留内存, 二维码是唯一落点。
 * - 导入: 扫码解析 → KeyPayloadSerializer.validate 校验曲线与公私钥
 *   匹配 → UI 展示指纹确认 + 指纹门 → 复用 [PrivateKeyManager.importKey]
 *   (含公私钥匹配复验) → Keystore 加密落盘。
 *
 * 不做的事 (原则红线):
 * - 不提供文件/网络通道 —— 迁移只走光学二维码
 * - 不导出 "全部绑定" 的打包文件 —— 逐绑定迁移, 每个绑定独立过指纹门
 * - 不在导出侧缓存明文 —— 序列化完成即刻零覆写
 */
class MigrationManager(context: Context) {

    private val appContext = context.applicationContext
    private val secureStorage = SecureStorage(appContext)
    private val keystoreManager = KeystoreManager()
    private val privateKeyManager = PrivateKeyManager(appContext)

    /** 导出结果: 迁移二维码 JSON + 指纹 (供界面展示与核对) */
    data class ExportedPayload(val json: String, val fingerprint: String)

    /**
     * 导出某应用的绑定私钥为迁移二维码 JSON。
     *
     * 调用前提: UI 层已完成 BIOMETRIC_STRONG 指纹门验证 —— 本类不再
     * 二次验证 (门由交互层统一管理, 与 VerifyActivity 模式一致)。
     *
     * 内存卫生: 解密出的 PKCS#8 明文在序列化完成后立即零字节覆写,
     * 与 PrivateKeyManager.signChallenge 同一纪律。
     */
    fun exportBinding(appPackage: String): ExportedPayload {
        val fingerprint = secureStorage.getFingerprint(appPackage)
            ?: throw IllegalStateException("该应用没有绑定密钥")
        val publicKey = secureStorage.getPublicKey(appPackage)
            ?: throw IllegalStateException("绑定数据不完整 (缺少公钥)")

        val (cipher, iv) = secureStorage.retrieveEncryptedPrivateKey(appPackage)
            ?: throw IllegalStateException("绑定数据不完整 (缺少私钥密文)")

        // Keystore 解密 → PKCS#8 明文 (仅此处驻留, 序列化后立即覆写)
        val privateKeyPlaintext = keystoreManager.decrypt(cipher, iv)
        return try {
            val json = MigrationPayload.serialize(appPackage, publicKey, privateKeyPlaintext)
            ExportedPayload(json = json, fingerprint = fingerprint)
        } finally {
            privateKeyManager.zeroMemory(privateKeyPlaintext)
        }
    }

    /**
     * 导入迁移二维码 JSON 为新 Vault 的绑定。
     *
     * 校验链: 反序列化 → [KeyPayloadSerializer.validate] (曲线 + 公私钥
     * 匹配) → importKey 内部再验一次并覆盖同应用旧绑定。
     *
     * 应用显示名: 新设备上用 PackageManager 权威解析 (不信任载荷文本),
     * 解析失败回退包名 —— 与 IPC 导入路径同一规则。
     */
    fun importMigration(raw: String): PrivateKeyManager.ImportResult {
        val data = MigrationPayload.deserialize(raw)
            ?: throw IllegalArgumentException("二维码不是有效的 Vault 迁移载荷")
        if (!KeyPayloadSerializer.validate(data.keyPair)) {
            throw IllegalArgumentException("迁移载荷中的密钥对不合法")
        }
        return privateKeyManager.importKey(
            keyData = data.keyPair,
            appPackage = data.appPackage,
            appLabel = resolveAppLabel(data.appPackage)
        )
    }

    /** 迁移目标应用是否已有绑定 (导入确认页的 "将替换" 警告) */
    fun hasExistingBinding(appPackage: String): Boolean =
        secureStorage.hasBinding(appPackage)

    /** 枚举可导出绑定 (状态页同款数据) */
    fun listBindings(): List<SecureStorage.StoredBinding> =
        secureStorage.getAllBindings()

    private fun resolveAppLabel(appPackage: String): String = try {
        appContext.packageManager.getApplicationLabel(
            appContext.packageManager.getApplicationInfo(appPackage, 0)
        ).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        appPackage
    }
}
