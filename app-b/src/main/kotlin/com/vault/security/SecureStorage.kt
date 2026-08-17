package com.vault.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.Base64

/**
 * 加密存储层 (v3: 多应用绑定)。
 *
 * 职责: 持久化保存 "加密后的私钥密文" 及其元数据, 永不存储私钥明文。
 *
 * 存储栈 (双层加密):
 * 1. Master key: 由 [MasterKeys.getOrCreate] 在 AndroidKeyStore 中生成的 AES-256-GCM 密钥保护。
 * 2. EncryptedSharedPreferences: 文件级再加密 (PrefKey = AES256-SIV, PrefValue = AES256-GCM)。
 *
 * v3 数据模型: 以 "来源应用包名" 为主键 (每个接入应用一把活动密钥):
 * - 私钥密文 (Base64, 已含 GCM AuthTag)
 * - IV (Base64)
 * - 公钥 (Base64) / 公钥指纹
 * - 应用显示名 (导入时由 PackageManager 解析, 供状态页区分来源)
 * - 绑定时间戳
 *
 * 迁移: v2 旧格式以指纹为主键 (单活动密钥), 首次访问时自动整体迁移为
 * Engine (com.engine) 的绑定记录, 老用户升级后无需重新绑定。
 *
 * 注: 指纹本身非敏感 (由公钥 SHA-256 派生), 用于保险箱状态页展示与索引。
 */
class SecureStorage(context: Context) {

    companion object {
        private const val PREFS_FILE = "vault_secure_prefs"

        // 以来源应用包名为主键的字段前缀 (v3)
        private const val PREFIX_PRIV_CIPHER = "priv_cipher_"
        private const val PREFIX_PRIV_IV = "priv_iv_"
        private const val PREFIX_PUB = "pub_"
        private const val PREFIX_FP = "fp_"
        private const val PREFIX_LABEL = "label_"
        private const val PREFIX_TIMESTAMP = "ts_"

        // 应用绑定索引 (逗号分隔包名), 便于枚举已绑定应用
        private const val KEY_APP_INDEX = "app_index"
        private const val SEPARATOR = ","

        // ---- v2 旧格式 (指纹主键), 仅用于迁移 ----
        private const val LEGACY_FINGERPRINT_INDEX = "fingerprint_index"

        /** 迁移目标: v2 单密钥模型只服务 Engine 一个应用 */
        private const val LEGACY_TARGET_PACKAGE = "com.engine"
        private const val LEGACY_TARGET_LABEL = "Engine"
    }

    private val masterKeyAlias: String =
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        PREFS_FILE,
        masterKeyAlias,
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** 状态页展示条目: 来源应用 + 指纹 + 绑定时间 */
    data class StoredBinding(
        val appPackage: String,
        val appLabel: String,
        val fingerprint: String,
        val importedAt: Long
    )

    init {
        migrateLegacyIfNeeded()
    }

    /**
     * v2 (指纹主键, 单活动密钥) → v3 (包名主键) 一次性迁移。
     * 迁移后清除旧键, 重复调用无副作用。
     */
    private fun migrateLegacyIfNeeded() {
        if (getAppIndex().isNotEmpty()) return

        val legacyIndex = prefs.getString(LEGACY_FINGERPRINT_INDEX, null)
            ?.split(SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        if (legacyIndex.isEmpty()) return

        // 旧模型仅存一把密钥, 最近导入的为活动密钥
        val latest = legacyIndex
            .map { fp -> fp to (prefs.getString("ts_$fp", null)?.toLongOrNull() ?: 0L) }
            .maxByOrNull { it.second }
            ?.first ?: return

        val cipher = prefs.getString("priv_cipher_$latest", null)
        val iv = prefs.getString("priv_iv_$latest", null)
        val pub = prefs.getString("pub_$latest", null)
        if (cipher == null || iv == null || pub == null) return

        prefs.edit()
            .putString(PREFIX_PRIV_CIPHER + LEGACY_TARGET_PACKAGE, cipher)
            .putString(PREFIX_PRIV_IV + LEGACY_TARGET_PACKAGE, iv)
            .putString(PREFIX_PUB + LEGACY_TARGET_PACKAGE, pub)
            .putString(PREFIX_FP + LEGACY_TARGET_PACKAGE, latest)
            .putString(PREFIX_LABEL + LEGACY_TARGET_PACKAGE, LEGACY_TARGET_LABEL)
            .putString(PREFIX_TIMESTAMP + LEGACY_TARGET_PACKAGE,
                (prefs.getString("ts_$latest", null)?.toLongOrNull() ?: 0L).toString())
            .putString(KEY_APP_INDEX, LEGACY_TARGET_PACKAGE)
            // 清除全部旧格式键
            .apply {
                legacyIndex.forEach { fp ->
                    remove("priv_cipher_$fp").remove("priv_iv_$fp")
                        .remove("pub_$fp").remove("ts_$fp")
                }
                remove(LEGACY_FINGERPRINT_INDEX)
            }
            .apply()
    }

    /**
     * 存储/覆盖某个应用的绑定密钥 (v3: 导入即替换该应用的旧密钥, 其他应用不受影响)。
     */
    fun storeBinding(
        appPackage: String,
        appLabel: String,
        fingerprint: String,
        privateKeyCipher: ByteArray,
        iv: ByteArray,
        publicKey: ByteArray,
        importedAt: Long
    ) {
        val encoder = Base64.getEncoder()
        prefs.edit().apply {
            putString(PREFIX_PRIV_CIPHER + appPackage, encoder.encodeToString(privateKeyCipher))
            putString(PREFIX_PRIV_IV + appPackage, encoder.encodeToString(iv))
            putString(PREFIX_PUB + appPackage, encoder.encodeToString(publicKey))
            putString(PREFIX_FP + appPackage, fingerprint)
            putString(PREFIX_LABEL + appPackage, appLabel)
            putString(PREFIX_TIMESTAMP + appPackage, importedAt.toString())
        }.apply()

        val index = getAppIndex().toMutableSet()
        index.add(appPackage)
        prefs.edit().putString(KEY_APP_INDEX, index.joinToString(SEPARATOR)).apply()
    }

    /**
     * 取回某应用绑定私钥的密文与 IV (待 Keystore 解密)。
     */
    fun retrieveEncryptedPrivateKey(appPackage: String): Pair<ByteArray, ByteArray>? {
        val cipherB64 = prefs.getString(PREFIX_PRIV_CIPHER + appPackage, null) ?: return null
        val ivB64 = prefs.getString(PREFIX_PRIV_IV + appPackage, null) ?: return null
        val decoder = Base64.getDecoder()
        return decoder.decode(cipherB64) to decoder.decode(ivB64)
    }

    /**
     * 取回某应用绑定的公钥指纹。
     */
    fun getFingerprint(appPackage: String): String? {
        return prefs.getString(PREFIX_FP + appPackage, null)
    }

    /**
     * 删除某个应用的绑定 (重新导入前调用; 其他应用的绑定保留)。
     */
    fun deleteBinding(appPackage: String) {
        prefs.edit()
            .remove(PREFIX_PRIV_CIPHER + appPackage)
            .remove(PREFIX_PRIV_IV + appPackage)
            .remove(PREFIX_PUB + appPackage)
            .remove(PREFIX_FP + appPackage)
            .remove(PREFIX_LABEL + appPackage)
            .remove(PREFIX_TIMESTAMP + appPackage)
            .apply()

        val index = getAppIndex().toMutableSet()
        index.remove(appPackage)
        prefs.edit().putString(KEY_APP_INDEX, index.joinToString(SEPARATOR)).apply()
    }

    /** 是否存在任意绑定密钥 */
    fun hasStoredKey(): Boolean = getAppIndex().isNotEmpty()

    /** 指定应用是否已绑定密钥 */
    fun hasBinding(appPackage: String): Boolean = appPackage in getAppIndex()

    /**
     * 返回所有应用绑定 (按绑定时间倒序), 供保险箱状态页展示。
     */
    fun getAllBindings(): List<StoredBinding> {
        return getAppIndex().mapNotNull { pkg ->
            val fp = prefs.getString(PREFIX_FP + pkg, null) ?: return@mapNotNull null
            StoredBinding(
                appPackage = pkg,
                appLabel = prefs.getString(PREFIX_LABEL + pkg, null) ?: pkg,
                fingerprint = fp,
                importedAt = prefs.getString(PREFIX_TIMESTAMP + pkg, null)?.toLongOrNull() ?: 0L
            )
        }.sortedByDescending { it.importedAt }
    }

    /**
     * 返回最近绑定的应用包名 (无参调用方的默认路由), 无则 null。
     */
    fun latestAppPackage(): String? = getAllBindings().firstOrNull()?.appPackage

    private fun getAppIndex(): Set<String> {
        val raw = prefs.getString(KEY_APP_INDEX, null) ?: return emptySet()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }
}
