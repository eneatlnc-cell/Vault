package com.securesocial.core.ipc

import android.net.Uri

/**
 * IPC URI Scheme 契约 (Engine ↔ Vault)
 *
 * 安全模型 (v2, 修复隐式 Intent 广播式投递与回调伪造):
 * - 所有跨 App 唤起必须使用显式包名投递 (Intent.setPackage), 消除 chooser 误选与恶意 App 抢答
 * - 组件受 signature 级自定义权限保护, 只有与 Vault 同证书签名的应用才能唤起
 * - 移除 BROWSABLE 类别, 浏览器/任意网页无法唤起 IPC 入口
 * - 所有回调必须携带 ECDSA 签名 (sig = Sign_vault(sessionId ‖ status ‖ ts)),
 *   Engine 用绑定公钥验签 + 时间戳窗口校验, 防止回调伪造与重放
 * - 私钥仍仅经二维码光学通道或受保护的 Intent Extra (import) 传递
 *
 * URI 格式:
 * - 唤起导入:  myvault://import?session=<sessionId>
 * - 唤起验证:  myvault://verify?session=<sessionId>
 * - 签名请求:  myvault://sign?session=<sessionId>&payload=<urlencoded base64>
 * - 成功回调:  myvault://callback?session=<id>&status=success&ts=<millis>&sig=<base64>[&result=<base64>]
 * - 失败回调:  myvault://callback?session=<id>&status=fail&code=<err>&ts=<millis>&sig=<base64>
 */
object IpcContract {
    const val SCHEME = "myvault"
    const val HOST_IMPORT = "import"
    const val HOST_CALLBACK = "callback"
    const val HOST_VERIFY = "verify"
    const val HOST_SIGN = "sign"

    const val PARAM_SESSION = "session"
    const val PARAM_STATUS = "status"
    const val PARAM_CODE = "code"
    const val PARAM_PAYLOAD = "payload"
    const val PARAM_RESULT = "result"
    const val PARAM_TS = "ts"
    const val PARAM_SIG = "sig"

    const val STATUS_SUCCESS = "success"
    const val STATUS_FAIL = "fail"

    // ---- 显式投递目标与权限 (v2) --------------------------------------------

    /** Vault 应用包名: Engine 发起的全部 IPC Intent 必须锁定此包名 */
    const val VAULT_PACKAGE = "com.vault"

    /** Engine 应用包名: Vault 发起的回调 Intent 必须锁定此包名 */
    const val ENGINE_PACKAGE = "com.engine"

    /**
     * Vault 侧 signature 级自定义权限。
     * 保护 Vault 的 import/verify/sign 入口组件: 仅与 Vault 同一证书签名的应用
     * (即 Engine, 开发期共用 debug keystore / 发布期需统一 release 签名) 可唤起。
     */
    const val VAULT_IPC_PERMISSION = "com.vault.permission.VAULT_IPC"

    /**
     * Engine 侧 signature 级自定义权限。
     * 保护 Engine 的 callback 入口组件: 仅持有该权限者 (即 Vault) 可投递登录/签名回调。
     */
    const val ENGINE_CALLBACK_PERMISSION = "com.engine.permission.ENGINE_CALLBACK"

    /** 回调签名验证的时间戳容忍窗口 (毫秒): ±120 秒 */
    const val CALLBACK_TS_TOLERANCE_MS = 120_000L

    /**
     * Intent Extra 键: 用于直接传递密钥二维码载荷 (JSON 字符串)。
     *
     * 当 Engine 通过 "一键唤起" 调起 Vault 时, 将 QR payload 作为 Intent Extra 传递,
     * Vault 可直接解析而无需开启摄像头扫描。
     * 安全约束 (v2): 该通道仅在 signature 权限 + 显式包名投递保护下使用,
     * 恶意 App 既无法收到此 Intent, 也无法伪造回调。
     */
    const val EXTRA_PAYLOAD = "extra_payload"

    /**
     * 构建回调签名内容: sessionId ‖ status ‖ ts (均 UTF-8 字节顺序拼接)
     *
     * Vault 对该内容做 ECDSA P-256 签名后附在回调 URI 的 sig 参数中,
     * Engine 用绑定身份公钥验签 —— 回调从 "状态字符串" 升级为 "私钥持有证明"。
     */
    fun callbackSigningContent(sessionId: String, status: String, ts: String): ByteArray {
        return (sessionId + status + ts).toByteArray(Charsets.UTF_8)
    }

    /**
     * 构建唤起 App B 导入的 URI
     */
    fun buildImportUri(sessionId: String): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_IMPORT)
            .appendQueryParameter(PARAM_SESSION, sessionId)
            .build()
            .toString()
    }

    /**
     * 构建成功回调 URI (携带 sessionId / 时间戳 / ECDSA 签名)
     *
     * @param signature 对 callbackSigningContent(sessionId, "success", ts) 的 ECDSA 签名
     * @param result    可选的业务结果 (如签名请求返回的签名字节, Base64)
     */
    fun buildSuccessCallbackUri(
        sessionId: String,
        ts: Long,
        signature: String,
        result: String? = null
    ): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_CALLBACK)
            .appendQueryParameter(PARAM_SESSION, sessionId)
            .appendQueryParameter(PARAM_STATUS, STATUS_SUCCESS)
            .appendQueryParameter(PARAM_TS, ts.toString())
            .appendQueryParameter(PARAM_SIG, signature)
            .apply { if (result != null) appendQueryParameter(PARAM_RESULT, result) }
            .build()
            .toString()
    }

    /**
     * 构建失败回调 URI (同样携带时间戳与签名)
     */
    fun buildFailCallbackUri(
        errorCode: IpcErrorCode,
        sessionId: String,
        ts: Long,
        signature: String
    ): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_CALLBACK)
            .appendQueryParameter(PARAM_SESSION, sessionId)
            .appendQueryParameter(PARAM_STATUS, STATUS_FAIL)
            .appendQueryParameter(PARAM_CODE, errorCode.code)
            .appendQueryParameter(PARAM_TS, ts.toString())
            .appendQueryParameter(PARAM_SIG, signature)
            .build()
            .toString()
    }

    /**
     * 构建唤起指纹验证的 URI
     */
    fun buildVerifyUri(sessionId: String): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_VERIFY)
            .appendQueryParameter(PARAM_SESSION, sessionId)
            .build()
            .toString()
    }

    /**
     * 构建签名请求 URI。
     *
     * Engine 将待签字节 (Base64) 发给 Vault, Vault 验证生物识别后用
     * 绑定的身份私钥做 ECDSA 签名, 签名结果经 callback 的 result 参数返回。
     * 典型用途: 中继注册挑战应答、ECDH 信号公钥签名。
     */
    fun buildSignUri(sessionId: String, payloadBase64: String): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_SIGN)
            .appendQueryParameter(PARAM_SESSION, sessionId)
            .appendQueryParameter(PARAM_PAYLOAD, payloadBase64)
            .build()
            .toString()
    }

    /** 检查 URI 是否为 IPC 唤起请求 */
    fun isImportUri(uri: Uri): Boolean =
        uri.scheme == SCHEME && uri.host == HOST_IMPORT

    /** 检查 URI 是否为 IPC 回调 */
    fun isCallbackUri(uri: Uri): Boolean =
        uri.scheme == SCHEME && uri.host == HOST_CALLBACK

    /** 检查 URI 是否为指纹验证请求 */
    fun isVerifyUri(uri: Uri): Boolean =
        uri.scheme == SCHEME && uri.host == HOST_VERIFY

    /** 检查 URI 是否为签名请求 */
    fun isSignUri(uri: Uri): Boolean =
        uri.scheme == SCHEME && uri.host == HOST_SIGN
}
