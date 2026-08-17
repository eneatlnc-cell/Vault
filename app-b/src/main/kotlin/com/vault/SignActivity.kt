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
 * 签名请求入口 (v3.3)
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
 * v3.3 修复 (Vault 关闭后无法被唤起):
 * - 移除 manifest 的 noHistory (透明页冷启动过渡被系统 pause 即销毁,
 *   指纹框无机会展示; 对照能正常工作的 ImportActivity 没有 noHistory)
 * - IpcReceiver 延迟到回送时构造; PrivateKeyManager 预检全程 try-catch,
 *   存储异常回送 SIGN_FAILED 而非崩溃
 * - 回送路径异常兜底, 任何失败都保证 finish()
 *
 * v3.2 修复保持:
 * - onNewIntent / onResume 统一走 maybeShowPrompt() 幂等入口
 * - ERROR_CANCELED 自动重试一次; RESUMED 才 authenticate; 不设 FLAG_SECURE
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

    /** 已回送并结束 (防重复回调) */
    private var finished = false

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
     * 统一的指纹框展示入口: 幂等可重入。
     */
    private fun maybeShowPrompt() {
        if (!promptShown && !isFinishing && !finished &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startBiometricAndSign()
        }
    }

    /**
     * 指纹验证后执行签名
     *
     * 预检 (绑定状态) 是唯一需要存储 I/O 的前置步骤, 全程 try-catch;
     * 其余路径 (生物能力检查/指纹框) 保持零 I/O 轻路径。
     */
    private fun startBiometricAndSign() {
        val sessionId = pendingSessionId
        val payloadBytes = pendingPayload
        if (sessionId == null) {
            finish()
            return
        }
        val appPackage = pendingAppPackage

        fun respond(success: Boolean, errorCode: IpcErrorCode? = null, resultBase64: String? = null) {
            if (finished) return
            finished = true
            try {
                // IpcReceiver 延迟至此构造 (v3.3: 指纹框展示前零 Keystore I/O)
                IpcReceiver(this, appPackage).sendCallback(
                    IpcCallback(
                        sessionId = sessionId,
                        isSuccess = success,
                        errorCode = errorCode
                    ),
                    resultBase64 = resultBase64
                )
            } catch (t: Throwable) {
                Log.e(TAG, "sendCallback failed: ${t.message}")
            } finally {
                finish()
            }
        }

        // 1. 载荷合法性
        if (payloadBytes == null || payloadBytes.isEmpty() || payloadBytes.size > MAX_SIGN_PAYLOAD_BYTES) {
            respond(false, IpcErrorCode.SIGN_FAILED)
            return
        }

        // 2. 该应用必须已绑定密钥 (存储预检, 异常回送失败而非崩溃)
        val privateKeyManager = try {
            PrivateKeyManager(this)
        } catch (t: Throwable) {
            Log.e(TAG, "PrivateKeyManager init failed: ${t.message}")
            respond(false, IpcErrorCode.SIGN_FAILED)
            return
        }
        val hasKey = try {
            privateKeyManager.hasStoredKey(appPackage)
        } catch (t: Throwable) {
            Log.e(TAG, "hasStoredKey failed: ${t.message}")
            false
        }
        if (!hasKey) {
            respond(false, IpcErrorCode.NO_KEY_BOUND)
            return
        }

        // 3. 生物识别可用性 (纯内存查询)
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
                    // 指纹通过 → 用该应用绑定的私钥签名 (P-256 毫秒级)
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
                            if (canceledRetryCount < 1 && !finished && !isFinishing) {
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
