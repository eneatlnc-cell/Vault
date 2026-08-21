package com.vault.migration

import com.securesocial.core.crypto.KeyPayloadSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Base64

/**
 * Vault 绑定迁移载荷 (v3.18.0)。
 *
 * 场景: 换机 / 重装 Vault / 设备丢失后, 把 "应用绑定私钥" 从旧 Vault
 * 转移到新 Vault。旧 Vault 指纹门后导出二维码 → 新 Vault 扫码导入,
 * 全程光学通道, 不触网、不落明文盘。
 *
 * 设计原则 (与项目密钥原则一致):
 * - 私钥只在两处存在: Keystore 加密密文 (静止) 与二维码光学通道 (移动)
 * - 出门 (导出) 与进门 (导入) 双侧均设 BIOMETRIC_STRONG 指纹门
 * - 载荷不携带应用显示名 —— 新设备用 PackageManager 权威解析,
 *   不信任迁移载荷中的自由文本
 *
 * 载荷结构 (JSON, 与 Engine 密钥二维码的 KeyPayload 同构 + app 路由字段):
 * {"v":1,"app":"com.engine","pub":"<X.509 B64>","prv":"<PKCS#8 B64>","curve":"P-256"}
 *
 * 容量: EC P-256 密钥对序列化约 350 字符, QR version ~11 完全容纳,
 * 与 Engine 密钥二维码同级, 无需分段。
 */
object MigrationPayload {

    private const val SUPPORTED_VERSION = 1
    private const val SUPPORTED_CURVE = "P-256"

    @Serializable
    private data class Payload(
        val v: Int,
        val app: String,
        val pub: String,
        val prv: String,
        val curve: String
    )

    /** 解析后的迁移数据 (公私钥为编码字节, 待导入校验) */
    data class MigrationData(
        val appPackage: String,
        val keyPair: KeyPayloadSerializer.KeyPairData
    )

    /** 序列化某应用的绑定密钥为迁移二维码 JSON */
    fun serialize(appPackage: String, publicKey: ByteArray, privateKey: ByteArray): String {
        val payload = Payload(
            v = SUPPORTED_VERSION,
            app = appPackage,
            pub = Base64.getEncoder().encodeToString(publicKey),
            prv = Base64.getEncoder().encodeToString(privateKey),
            curve = SUPPORTED_CURVE
        )
        return Json.encodeToString(payload)
    }

    /**
     * 解析迁移二维码 JSON; 任何字段缺失 / 版本或曲线不符 / Base64 畸形
     * 均返回 null (导入侧凭 null 引导用户重扫, 不崩溃)。
     */
    fun deserialize(raw: String): MigrationData? {
        return try {
            val payload = Json {
                ignoreUnknownKeys = true
            }.decodeFromString(Payload.serializer(), raw)

            if (payload.v != SUPPORTED_VERSION) return null
            if (payload.curve != SUPPORTED_CURVE) return null
            if (payload.app.isBlank()) return null

            val decoder = Base64.getDecoder()
            MigrationData(
                appPackage = payload.app,
                keyPair = KeyPayloadSerializer.KeyPairData(
                    publicKey = decoder.decode(payload.pub),
                    privateKey = decoder.decode(payload.prv),
                    curve = payload.curve
                )
            )
        } catch (e: Exception) {
            null
        }
    }
}
