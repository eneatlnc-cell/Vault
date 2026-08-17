package com.vault.security

/**
 * 生物识别授权缓存 (v3.5)
 *
 * 问题背景: Engine 的 "登录 → 中继挑战签名" 链路会让用户在几秒内
 * 被要求连续按两次指纹 (登录验证一次 + 挑战签名一次); 绑定流程则是
 * "导入指纹 → 登录指纹 → 挑战指纹" 三连。用户视角即 "指纹框死循环"。
 *
 * 方案: 一次成功的强生物识别 (BIOMETRIC_STRONG) 授权一个短时效窗口
 * (默认 30s)。窗口内的后续 Sign 请求静默完成签名, 不再弹第二次指纹框。
 *
 * 安全性说明:
 * - 本 App 的私钥保护本来就是 App 层生物识别门 (Keystore 对称加密 +
 *   BiometricPrompt 门禁), 非硬件强制 per-use 认证; 窗口化授权与
 *   Android 官方 setUserAuthenticationValidityDurationSeconds 语义一致
 * - 窗口仅 30s, 仅驻内存 (进程被杀即失效), 不跨重启
 * - 仅 Vault 本进程可读写, 组件均受 signature 权限保护
 */
object AuthGrantCache {

    /** 最近一次生物识别成功的时间戳 (0 = 从未) */
    @Volatile
    private var lastGrantedAt: Long = 0L

    /** 记录一次成功的生物识别 */
    fun grant() {
        lastGrantedAt = System.currentTimeMillis()
    }

    /**
     * 当前是否处于有效授权窗口内
     *
     * @param windowMs 窗口时长, 默认 30 秒
     */
    fun isRecentlyGranted(windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        val grantedAt = lastGrantedAt
        if (grantedAt <= 0L) return false
        val elapsed = System.currentTimeMillis() - grantedAt
        return elapsed in 0..windowMs
    }

    private const val DEFAULT_WINDOW_MS = 30_000L
}
