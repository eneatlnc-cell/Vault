package com.vault

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver
import com.vault.security.PrivateKeyManager
import java.util.Base64

/**
 * 签名请求入口 (v3.2)
 *
 * 签名流程目标交互 (用户定义):
 *   Engine 请求签名 → 唤起本页 (透明, 无可见 UI) → 系统指纹框 →
 *   指纹通过 → 用该应用绑定私钥签名 → 自动回送结果 (Engine 回前台) →
 *   本页 finish()。全程无中间停留页面。
 *
 * 典型用途 (Engine 侧发起):
 * - 中继注册挑战应答: Sign("RELAY-AUTH-V1" ‖ fingerprint ‖ nonce)
 * - ECDH 信令签名:    Sign("ENGINE-SIGNAL-V1" ‖ ecdhPub ‖ senderFp ‖ receiverFp)
 *
 * v3.2 修复 (指纹框不弹/卡死):
 * - onNewIntent / onResume 统一走 maybeShowPrompt()
 *   (旧版 onNewIntent 重置 promptShown 后, 已 RESUMED 的实例
 *    不会再触发 onResume, 指纹框永不展示)
 *
 * v3 修复保持: RESUMED 才 authenticate / ERROR_CANCELED 重试一次 /
 * 无 FLAG_SECURE / singleTask 防多实例叠加。
 *
 * 安全约束:
 * - 组件受 signature 级权限保护, 仅同证书应用可唤起
 * - 载荷大小硬上限 4KB, 防签名预言机滥用
 * - 私钥全程 Keystore 加密域, 签名后明文零字节覆写
 */
class SignActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VaultSign"

        /** 签名载荷大小上限 (字节) */
        private const val MAX_SIGN_PAYLOAD_BYTES = 4096
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE
    private var pendingPayload: ByteArray? = null

    /** 本次生命周期内指纹框是否已展示 */
    private var promptShown = false

    /** ERROR_CANCELED 自动重试次数 (仅一次) */
    private var canceledRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!parseAndValidate(intent)) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 复用实例: 以最新请求为准
        if (parseAndValidate(intent)) {
            promptShown = false
            canceledRetryCount = 0
            // v3.2: 已 RESUMED 时必须在此主动展示
            maybeShowPrompt()
        } else {
            finish()
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
        maybeShowPrompt()
    }

    /**
     * 统一的指纹框展示入口 (v3.2): 幂等可重入。
     */
    private fun maybeShowPrompt() {
        if (!promptShown && !isFinishing &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
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
            // 回调显式拉起发起方 (Engine), Engine 任务回到前台
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
            .setSubtitle("请求使用保险箱密钥签名")
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
                        BiometricPrompt.ERROR_CANCELED -> {
                            if (canceledRetryCount < 1) {
                                canceledRetryCount++
                                promptShown = false
                                window.decorView.post { maybeShowPrompt() }
                            } else {
                                respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
                            }
                        }
                        else -> respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
                    }
                }
            }
        )

        promptShown = true
        prompt.authenticate(promptInfo)
    }
}
