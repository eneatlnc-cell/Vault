package com.vault.ipc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.securesocial.core.ipc.IpcImportRequest
import com.securesocial.core.ipc.IpcSignRequest
import com.vault.security.PrivateKeyManager

/**
 * IPC 接收/回送层 (v3)。
 *
 * 职责:
 * - [handleIntent]: 解析 myvault://import 唤起请求 (sessionId + 来源应用 + 可选 payload Extra)
 * - [parseSignRequest]: 解析 myvault://sign 签名请求
 * - [sendCallback]: 回送携带 ECDSA 签名的 myvault://callback 结果
 * - [resolveAppLabel]: 由包名解析来源应用显示名 (PackageManager 权威解析, 不信任传入名)
 *
 * v3 多应用绑定:
 * - 实例绑定一个来源应用 (appPackage), 回调签名使用 "该应用专属的绑定私钥"
 * - 无显式 app 参数时默认 Engine (com.engine), 兼容旧客户端
 *
 * v2 安全增强 (修复: 隐式 Intent 广播式投递 / 回调伪造):
 * - 回调 Intent 显式锁定 Engine 包名 (setPackage), 不再向系统广播
 * - 回调必须携带签名: sig = Sign_vault(sessionId ‖ status ‖ ts)
 * - 无绑定密钥时只能发送未签名回调, Engine 侧按规则接受 (仅限失败回调)
 */
class IpcReceiver(
    private val context: Context,
    private val appPackage: String = IpcContract.ENGINE_PACKAGE
) {

    companion object {
        private const val TAG = "VaultIpc"
    }

    private val privateKeyManager = PrivateKeyManager(context.applicationContext)
    private val b64e = java.util.Base64.getEncoder()

    /** 本次会话对应的来源应用包名 (供导入流程落库) */
    val sourceAppPackage: String get() = appPackage

    /** 来源应用显示名 (PackageManager 权威解析; 解析失败回退包名) */
    val sourceAppLabel: String by lazy { resolveAppLabel(appPackage) }

    /**
     * 由包名解析应用显示名。
     *
     * 安全说明: 显示名从本机 PackageManager 读取 (权威来源), 不信任调用方
     * 传入的任何名称字段; 能到达此处的调用方已通过 signature 权限门禁。
     */
    private fun resolveAppLabel(pkg: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() }
                ?: pkg
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "App label unresolved for $pkg, fallback to package name")
            pkg
        }
    }

    /**
     * 解析唤起 Intent, 返回导入请求 (含 session 和可选 payload); 非 IPC 唤起返回 null。
     */
    fun handleIntent(intent: Intent?): IpcImportRequest? {
        if (intent == null) return null
        return IpcImportRequest.fromIntent(intent)
    }

    /**
     * 解析签名请求 (myvault://sign + EXTRA_PAYLOAD); 非法请求返回 null。
     *
     * v3.17: payload 优先读 Intent Extra (EXTRA_PAYLOAD), Extra 缺失时
     * 回退 URI 查询参数 (兼容旧版 Engine)。
     */
    fun parseSignRequest(intent: Intent?): IpcSignRequest? {
        if (intent == null) return null
        return IpcSignRequest.fromIntent(intent)
    }

    /**
     * 回送 IPC 结果 (v3: 按来源应用路由签名密钥)
     *
     * @param callback     回调状态 (sessionId / 成败 / 错误码)
     * @param resultBase64 业务结果 (签名请求返回的 Base64 签名字节), 仅成功时有意义
     *
     * 签名规则:
     * - 该应用已绑定密钥 → 用其绑定私钥对 (sessionId ‖ status ‖ ts) 签名
     * - 未绑定密钥 → 发送未签名回调 (仅导入流程的失败场景, Engine 会按规则接受)
     *
     * v3.17 载荷去 URI 化: URI 仅保留路由字段 (session/status/code/ts);
     * 签名与业务结果经 Intent Extra 投递 —— Intent data 会随
     * ActivityTaskManager 的 "START u0" 日志行整体进 logcat, Extra 不会。
     */
    fun sendCallback(callback: IpcCallback, resultBase64: String? = null) {
        val sessionId = callback.sessionId ?: return
        val status = if (callback.isSuccess) IpcContract.STATUS_SUCCESS else IpcContract.STATUS_FAIL
        val ts = System.currentTimeMillis()

        // 尝试用该应用的绑定密钥签名
        var signature: String? = null
        try {
            if (privateKeyManager.hasStoredKey(appPackage)) {
                val content = IpcContract.callbackSigningContent(sessionId, status, ts.toString())
                val sig = privateKeyManager.signChallenge(content, appPackage)
                signature = b64e.encodeToString(sig)
            }
        } catch (e: Exception) {
            // 签名失败 (Keystore 异常等): 回调降级为未签名, 由 Engine 侧规则裁决
            Log.w(TAG, "Callback signing failed: ${e.message}")
        }

        val callbackUri = if (callback.isSuccess) {
            IpcContract.buildSuccessCallbackUri(sessionId, ts)
        } else {
            IpcContract.buildFailCallbackUri(
                callback.errorCode ?: IpcErrorCode.UNKNOWN_ERROR,
                sessionId,
                ts
            )
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(callbackUri)).apply {
            // v3: 显式锁定来源应用包名 (回调回到发起方), 杜绝广播式投递被截获/冒充
            // appPackage=com.engine 时与 v2 行为一致
            setPackage(appPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // v3.17: 敏感字段走 Extra, 不进 URI (防系统日志泄露)
            if (signature != null) putExtra(IpcContract.EXTRA_SIG, signature)
            if (callback.isSuccess && resultBase64 != null) {
                putExtra(IpcContract.EXTRA_RESULT, resultBase64)
            }
        }

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            // v3.17.1: 记录真实异常类名 —— SecurityException 意味着与 Engine
            // 签名证书不一致 (signature 权限未授予), 与 "未安装" 的处置完全不同;
            // 旧实现两类混为一谈, 排障时无从下手。
            Log.w(
                TAG,
                "Callback delivery failed: ${it.javaClass.simpleName}: ${it.message} " +
                    "(SecurityException = 与 $appPackage 签名不一致; " +
                    "ActivityNotFoundException = 目标未安装)"
            )
        }
    }
}
