package com.vault.security

import java.security.MessageDigest

/**
 * 动态码生成器。
 *
 * 算法链路:
 *   challenge --(ECDSA 签名)--> signature --(SHA-256)--> digest --(截断)--> 8 位数字码
 *
 * 动态码有效期 30 秒; 调用方应使用基于时间窗口的 challenge
 * (如 epochSecond / 30) 以保证同一窗口内码值一致。
 *
 * 签名过程由 [PrivateKeyManager] 完成, 私钥明文在签名后立即零字节覆写。
 */
class DynamicCodeGenerator(private val privateKeyManager: PrivateKeyManager) {

    companion object {
        private const val CODE_LENGTH = 6
        private const val VALIDITY_MILLIS = 30_000L
    }

    data class DynamicCode(
        val code: String,
        val expiresAt: Long,
        val progress: Float // 1.0 = 全部剩余, 调用方按时间递减
    )

    /**
     * 基于挑战值生成 8 位数字动态码。
     */
    fun generateCode(challenge: ByteArray): DynamicCode {
        val signature = privateKeyManager.signChallenge(challenge)

        val digest = MessageDigest.getInstance("SHA-256").digest(signature)
        val code = truncateToDigits(digest)

        val now = System.currentTimeMillis()
        return DynamicCode(
            code = code,
            expiresAt = now + VALIDITY_MILLIS,
            progress = 1.0f
        )
    }

    /**
     * 将哈希前 4 字节解释为无符号 32 位整数, 对 10^6 取模并补零至 6 位。
     */
    private fun truncateToDigits(hash: ByteArray): String {
        var num = 0L
        val take = minOf(4, hash.size)
        for (i in 0 until take) {
            num = (num shl 8) or (hash[i].toLong() and 0xFF)
        }
        val code = num % pow10(CODE_LENGTH)
        return StringBuilder(CODE_LENGTH).apply {
            val s = code.toString()
            repeat(CODE_LENGTH - s.length) { append('0') }
            append(s)
        }.toString()
    }

    private fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }
}
