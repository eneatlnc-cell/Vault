package com.vault

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver
import com.vault.security.PrivateKeyManager
import java.util.Base64

/**
 * 签名请求入口 (v3)
 *
 * 接收 Engine 发来的 myvault://sign?session=<id>&app=<pkg>&payload=<base64> 请求:
 * 1. 校验请求合法性 (URI 可解析 + 载荷大小上限)
 * 2. 弹出系统指纹验证 (BiometricPrompt, BIOMETRIC_STRONG)
 * 3. 验证通过后用 "该应用绑定的身份私钥" 对载荷做 ECDSA P-256 签名
 * 4. 签名结果 (Base64) 经签名回调返回 Engine, 随后立即 finish()
 *
 * 典型用途 (Engine 侧发起):
 * - 中继注册挑战应答: Sign("RELAY-AUTH-V1" ‖ fingerprint ‖ nonce)
 * - ECDH 信令签名:    Sign("ENGINE-SIGNAL-V1" ‖ ecdhPub ‖ senderFp ‖ receiverFp)
 *
 * v3 修复/增强:
 * - 抖动修复: authenticate() 移至 onResume (RESUMED 状态), ERROR_CANCELED 自动
 *   重试一次, 移除 FLAG_SECURE (与指纹 overlay 冲突), singleTask 防多实例叠加
 * - 多应用路由: 按 URI app 参数取对应应用的绑定私钥签名
 *
 * 安全约束:
 * - 组件受 signature 级权限保护, 仅同证书应用可唤起 (恶意 App 无法借 Vault 签名)
 * - 载荷大小硬上限, 防止滥用为任意大数据签名预言机
 * - 全程私钥不出 Keystore 加密域, 签名后明文立即零字节覆写
 */
class SignActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VaultSign"

        /** 签名载荷大小上限 (字节): 挑战/信令均在 1KB 内, 4KB 已留足余量 */
        private const val MAX_SIGN_PAYLOAD_BYTES = 4096
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE
    private var pendingPayload: ByteArray? = null

    /** 本次生命周期内指纹框是否已展示 (防止 onResume 反复触发) */
    private var promptShown = false

    /** ERROR_CANCELED 自动重试次数 (仅一次, 防死循环) */
    private var canceledRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v3: 不再设置 FLAG_SECURE —— 页面无敏感内容, 且透明窗口 + FLAG_SECURE
        // 在部分机型与系统指纹对话框冲突导致闪烁 (抖动根因之一)
        if (!parseAndValidate(intent)) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 复用实例: 连续唤起以最新请求为准
        if (!parseAndValidate(intent)) {
            finish()
        } else {
            promptShown = false
            canceledRetryCount = 0
        }
    }

    /**
     * 解析并校验签名请求; 返回 false 表示请求非法 (应直接结束)。
     */
    private fun parseAndValidate(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        val sessionId = uri.getQueryParameter(IpcContract.PARAM_SESSION) ?: return false
        val payloadB64 = uri.getQueryParameter(IpcContract.PARAM_PAYLOAD) ?: return false

        pendingSessionId = sessionId
        pendingAppPackage = uri.getQueryParameter(IpcContract.PARAM_APP)
            ?.takeIf { it.isNotBlank() }
            ?: IpcContract.ENGINE_PACKAGE

        pendingPayload = try {
            Base64.getDecoder().decode(payloadB64)
        } catch (e: IllegalArgumentException) {
            null
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        // v3 修复核心: RESUMED 状态下才展示指纹框
        if (!promptShown && !isFinishing) {
            startBiometricAndSign()
        }
    }

    /**
     * 指纹验证后执行签名
     */
    private fun startBiometricAndSign() {
        val sessionId = pendingSessionId
        val appPackage = pendingAppPackage
        val payloadBytes = pendingPayload
        val ipcReceiver = IpcReceiver(this, appPackage)
        val privateKeyManager = PrivateKeyManager(this)

        fun respond(success: Boolean, errorCode: IpcErrorCode? = null, resultBase64: String? = null) {
            ipcReceiver.sendCallback(
                IpcCallback(
                    sessionId = sessionId,
                    isSuccess = success,
                    errorCode = errorCode
                ),
                resultBase64 = resultBase64
            )
            finish()
        }

        // 1. 载荷合法性
        if (payloadBytes == null || payloadBytes.isEmpty() || payloadBytes.size > MAX_SIGN_PAYLOAD_BYTES) {
            respond(false, IpcErrorCode.SIGN_FAILED)
            return
        }

        // 2. 该应用必须已绑定密钥
        if (!privateKeyManager.hasStoredKey(appPackage)) {
            respond(false, IpcErrorCode.NO_KEY_BOUND)
            return
        }

        // 3. 生物识别可用性
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            respond(false, IpcErrorCode.BIOMETRIC_UNAVAILABLE)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("身份签名请求")
            .setSubtitle("应用 [$appPackage] 请求使用保险箱密钥签名")
            .setNegativeButtonText("拒绝")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // 指纹通过 → 用该应用绑定的私钥签名 (P-256 毫秒级, 主线程可接受)
                    try {
                        val signature = privateKeyManager.signChallenge(payloadBytes, appPackage)
                        val sigB64 = Base64.getEncoder().encodeToString(signature)
                        respond(true, null, sigB64)
                    } catch (e: Exception) {
                        Log.e(TAG, "Sign failed: ${e.message}")
                        respond(false, IpcErrorCode.SIGN_FAILED, null)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        // 系统原因取消: 自动重试一次
                        BiometricPrompt.ERROR_CANCELED -> {
                            if (canceledRetryCount < 1) {
                                canceledRetryCount++
                                promptShown = false
                                window.decorView.post { showPrompt(prompt, promptInfo) }
                            } else {
                                respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
                            }
                        }
                        else -> respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
                    }
                }
            }
        )

        showPrompt(prompt, promptInfo)
    }

    private fun showPrompt(prompt: BiometricPrompt, promptInfo: BiometricPrompt.PromptInfo) {
        // 仅在未销毁且已 RESUMED 时展示
        if (!isFinishing && lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            )
        ) {
            promptShown = true
            prompt.authenticate(promptInfo)
        }
    }
}
