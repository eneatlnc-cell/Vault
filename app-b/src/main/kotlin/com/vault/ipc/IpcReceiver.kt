package com.vault.ipc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.securesocial.core.ipc.IpcImportRequest
import com.securesocial.core.ipc.IpcSignRequest
import com.vault.security.PrivateKeyManager

/**
 * IPC 接收/回送层 (v2)。
 *
 * 职责:
 * - [handleIntent]: 解析 myvault://import 唤起请求 (sessionId + 可选 payload Extra)
 * - [parseSignRequest]: 解析 myvault://sign 签名请求
 * - [sendCallback]: 回送携带 ECDSA 签名的 myvault://callback 结果
 *
 * v2 安全增强 (修复: 隐式 Intent 广播式投递 / 回调伪造):
 * - 回调 Intent 显式锁定 Engine 包名 (setPackage), 不再向系统广播,
 *   恶意 App 无法截获回调内容, 也无法冒充接收方
 * - 回调必须携带签名: sig = Sign_vault(sessionId ‖ status ‖ ts),
 *   Engine 用绑定公钥验签 + 时间戳窗口校验 —— 回调从 "状态字符串"
 *   升级为 "Vault 私钥持有证明"
 * - 无绑定密钥时 (如导入前的用户取消) 只能发送未签名回调,
 *   Engine 侧对该场景有明确的接受规则 (仅接受未签名的失败回调)
 *
 * 安全约束:
 * - URI 中绝不携带私钥明文, 仅承载 session 标识、状态码与签名
 */
class IpcReceiver(private val context: Context) {

    companion object {
        private const val TAG = "VaultIpc"
    }

    private val privateKeyManager = PrivateKeyManager(context.applicationContext)
    private val b64e = java.util.Base64.getEncoder()

    /**
     * 解析唤起 Intent, 返回导入请求 (含 session 和可选 payload); 非 IPC 唤起返回 null。
     *
     * payload 非空时, Vault 可直接解析密钥而无需开启摄像头扫描。
     */
    fun handleIntent(intent: Intent?): IpcImportRequest? {
        if (intent == null) return null
        return IpcImportRequest.fromIntent(intent)
    }

    /**
     * 解析签名请求 (myvault://sign?session=..&payload=..); 非法请求返回 null
     */
    fun parseSignRequest(intent: Intent?): IpcSignRequest? {
        val uri = intent?.data ?: return null
        return IpcSignRequest.fromUri(uri)
    }

    /**
     * 回送 IPC 结果 (v2: 签名 + 显式包名投递)
     *
     * @param callback     回调状态 (sessionId / 成败 / 错误码)
     * @param resultBase64 业务结果 (签名请求返回的 Base64 签名字节), 仅成功时有意义
     *
     * 签名规则:
     * - Vault 已绑定密钥 → 用活动私钥对 (sessionId ‖ status ‖ ts) 签名
     * - 未绑定密钥 → 发送未签名回调 (仅导入流程的失败场景, Engine 会按规则接受)
     */
    fun sendCallback(callback: IpcCallback, resultBase64: String? = null) {
        val sessionId = callback.sessionId ?: return
        val status = if (callback.isSuccess) IpcContract.STATUS_SUCCESS else IpcContract.STATUS_FAIL
        val ts = System.currentTimeMillis()

        // 尝试用活动密钥签名
        var signature: String? = null
        try {
            if (privateKeyManager.hasStoredKey()) {
                val content = IpcContract.callbackSigningContent(sessionId, status, ts.toString())
                val sig = privateKeyManager.signChallenge(content)
                signature = b64e.encodeToString(sig)
            }
        } catch (e: Exception) {
            // 签名失败 (Keystore 异常等): 回调降级为未签名, 由 Engine 侧规则裁决
            Log.w(TAG, "Callback signing failed: ${e.message}")
        }

        val callbackUri = if (callback.isSuccess) {
            IpcContract.buildSuccessCallbackUri(sessionId, ts, signature ?: "", resultBase64)
        } else {
            IpcContract.buildFailCallbackUri(
                callback.errorCode ?: IpcErrorCode.UNKNOWN_ERROR,
                sessionId,
                ts,
                signature ?: ""
            )
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(callbackUri)).apply {
            // v2: 显式锁定 Engine 包名, 杜绝广播式投递被恶意 App 截获/冒充
            setPackage(IpcContract.ENGINE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            // Engine 未安装/不可见: 静默忽略, 不影响 Vault 本地流程
            Log.i(TAG, "Callback delivered but Engine unavailable")
        }
    }
}
