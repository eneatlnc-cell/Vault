package com.vault

import android.app.Application

/**
 * App B 应用入口 - 物理隔离安全保险箱。
 *
 * 设计约束:
 * - 绝不申请 INTERNET 权限, 不引入任何第三方分析/埋点 SDK。
 * - 仅承担私钥硬件级安全存储与签名职责。
 *
 * 此处仅做轻量初始化; 不做任何网络相关操作。
 */
class VaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 安全保险箱无需复杂初始化; 敏感数据按需在 Keystore 中惰性生成。
    }
}
