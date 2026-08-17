package com.vault.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.Base64

/**
 * 加密存储层。
 *
 * 职责: 持久化保存 "加密后的私钥密文" 及其元数据, 永不存储私钥明文。
 *
 * 存储栈 (双层加密):
 * 1. Master key: 由 [MasterKeys.getOrCreate] 在 AndroidKeyStore 中生成的 AES-256-GCM 密钥保护。
 * 2. EncryptedSharedPreferences: 文件级再加密 (PrefKey = AES256-SIV, PrefValue = AES256-GCM)。
 *
 * 每个已导入密钥以公钥指纹为主键, 存储:
 * - 私钥密文 (Base64, 已含 GCM AuthTag)
 * - IV (Base64)
 * - 公钥 (Base64)
 * - 公钥指纹
 * - 导入时间戳
 *
 * 注: 指纹本身非敏感 (由公钥 SHA-256 派生), 用于保险箱状态页展示与索引。
 */
class SecureStorage(context: Context) {

    companion object {
        private const val PREFS_FILE = "vault_secure_prefs"

        // 以指纹为主键的字段前缀
        private const val PREFIX_PRIV_CIPHER = "priv_cipher_"
        private const val PREFIX_PRIV_IV = "priv_iv_"
        private const val PREFIX_PUB = "pub_"
        private const val PREFIX_TIMESTAMP = "ts_"

        // 指纹索引 (逗号分隔), 便于枚举已存储密钥
        private const val KEY_FINGERPRINT_INDEX = "fingerprint_index"
        private const val SEPARATOR = ","
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

    /**
     * 供状态页展示的指纹条目。
     */
    data class StoredFingerprint(
        val fingerprint: String,
        val importedAt: Long
    )

    /**
     * 存储一条已加密的私钥。
     */
    fun storePrivateKey(
        fingerprint: String,
        privateKeyCipher: ByteArray,
        iv: ByteArray,
        publicKey: ByteArray,
        importedAt: Long
    ) {
        val encoder = Base64.getEncoder()
        prefs.edit().apply {
            putString(PREFIX_PRIV_CIPHER + fingerprint, encoder.encodeToString(privateKeyCipher))
            putString(PREFIX_PRIV_IV + fingerprint, encoder.encodeToString(iv))
            putString(PREFIX_PUB + fingerprint, encoder.encodeToString(publicKey))
            putString(PREFIX_TIMESTAMP + fingerprint, importedAt.toString())
        }.apply()

        // 更新指纹索引 (去重)
        val index = getFingerprintIndex().toMutableSet()
        index.add(fingerprint)
        prefs.edit().putString(KEY_FINGERPRINT_INDEX, index.joinToString(SEPARATOR)).apply()
    }

    /**
     * 取回某指纹对应的私钥密文与 IV (待 Keystore 解密)。
     */
    fun retrieveEncryptedPrivateKey(fingerprint: String): Pair<ByteArray, ByteArray>? {
        val cipherB64 = prefs.getString(PREFIX_PRIV_CIPHER + fingerprint, null) ?: return null
        val ivB64 = prefs.getString(PREFIX_PRIV_IV + fingerprint, null) ?: return null
        val decoder = Base64.getDecoder()
        return decoder.decode(cipherB64) to decoder.decode(ivB64)
    }

    /**
     * 取回某指纹对应的公钥原始字节 (X.509)。
     */
    fun retrievePublicKey(fingerprint: String): ByteArray? {
        return prefs.getString(PREFIX_PUB + fingerprint, null)?.let {
            Base64.getDecoder().decode(it)
        }
    }

    /**
     * 取回某指纹的导入时间。
     */
    fun getImportedAt(fingerprint: String): Long {
        return prefs.getString(PREFIX_TIMESTAMP + fingerprint, null)?.toLongOrNull() ?: 0L
    }

    /**
     * 删除所有已存储密钥 (密文/IV/公钥/时间戳/索引)。
     * 导入新密钥前调用, 保证 "导入即替换" 的单活动密钥语义。
     */
    fun clearAllKeys() {
        val editor = prefs.edit()
        getFingerprintIndex().forEach { fp ->
            editor.remove(PREFIX_PRIV_CIPHER + fp)
                .remove(PREFIX_PRIV_IV + fp)
                .remove(PREFIX_PUB + fp)
                .remove(PREFIX_TIMESTAMP + fp)
        }
        editor.remove(KEY_FINGERPRINT_INDEX).apply()
    }

    /**
     * 是否存在已存储密钥。
     */
    fun hasStoredKey(): Boolean = getFingerprintIndex().isNotEmpty()

    /**
     * 返回所有已存储指纹及其导入时间 (按导入时间倒序), 供保险箱状态页展示。
     */
    fun getStoredFingerprints(): List<StoredFingerprint> {
        return getFingerprintIndex().map { fp ->
            StoredFingerprint(fingerprint = fp, importedAt = getImportedAt(fp))
        }.sortedByDescending { it.importedAt }
    }

    /**
     * 返回最近导入的指纹 (用于动态码签名的默认活动密钥), 无则 null。
     */
    fun latestFingerprint(): String? = getStoredFingerprints().firstOrNull()?.fingerprint

    private fun getFingerprintIndex(): Set<String> {
        val raw = prefs.getString(KEY_FINGERPRINT_INDEX, null) ?: return emptySet()
        return raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }
}
