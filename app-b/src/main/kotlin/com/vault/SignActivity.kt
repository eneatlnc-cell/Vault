package com.vault

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver
import com.vault.security.PrivateKeyManager
import java.util.Base64

/**
 * 签名请求入口 (v2 新增)
 *
 * 接收 Engine 发来的 myvault://sign?session=<id>&payload=<base64> 请求:
 * 1. 校验请求合法性 (URI 可解析 + 载荷大小上限)
 * 2. 弹出系统指纹验证 (BiometricPrompt, BIOMETRIC_STRONG)
 * 3. 验证通过后用绑定的身份私钥对载荷做 ECDSA P-256 签名
 * 4. 签名结果 (Base64) 经签名回调返回 Engine, 随后立即 finish()
 *
 * 典型用途 (Engine 侧发起):
 * - 中继注册挑战应答: Sign("RELAY-AUTH-V1" ‖ fingerprint ‖ nonce)
 * - ECDH 信令签名:    Sign("ENGINE-SIGNAL-V1" ‖ ecdhPub ‖ senderFp ‖ receiverFp)
 *
 * 安全约束:
 * - 组件受 signature 级权限保护, 仅 Engine 可唤起 (恶意 App 无法借 Vault 签名)
 * - 载荷大小硬上限, 防止滥用为任意大数据签名预言机
 * - 全程私钥不出 Keystore 加密域, 签名后明文立即零字节覆写
 * - 防截屏
 */
class SignActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VaultSign"

        /** 签名载荷大小上限 (字节): 挑战/信令均在 1KB 内, 4KB 已留足余量 */
        private const val MAX_SIGN_PAYLOAD_BYTES = 4096
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防截屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val ipcReceiver = IpcReceiver(this)

        fun respond(success: Boolean, errorCode: IpcErrorCode? = null, resultBase64: String? = null) {
            ipcReceiver.sendCallback(
                IpcCallback(
                    sessionId = pendingSessionId,
                    isSuccess = success,
                    errorCode = errorCode
                ),
                resultBase64 = resultBase64
            )
            finish()
        }

        // 1. 解析并校验请求
        val signRequest = ipcReceiver.parseSignRequest(intent)
        if (signRequest == null) {
            // 畸形请求: 无法路由回调, 直接结束
            finish()
            return
        }
        pendingSessionId = signRequest.sessionId

        val payloadBytes = signRequest.payloadBytes
        if (payloadBytes == null || payloadBytes.size > MAX_SIGN_PAYLOAD_BYTES) {
            respond(false, IpcErrorCode.SIGN_FAILED)
            return
        }

        // 2. Vault 必须已绑定密钥
        val privateKeyManager = PrivateKeyManager(this)
        if (!privateKeyManager.hasStoredKey()) {
            respond(false, IpcErrorCode.NO_KEY_BOUND)
            return
        }

        // 3. 生物识别 → 签名 → 回调
        startBiometricAndSign(payloadBytes, privateKeyManager, ::respond)
    }

    /** 本次签名请求的 sessionId (回调路由用) */
    private var pendingSessionId: String? = null

    /**
     * 指纹验证后执行签名
     *
     * @param respond 统一回送入口 (成功携带 Base64 签名, 失败携带错误码)
     */
    private fun startBiometricAndSign(
        payloadBytes: ByteArray,
        privateKeyManager: PrivateKeyManager,
        respond: (Boolean, IpcErrorCode?, String?) -> Unit
    ) {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            respond(false, IpcErrorCode.BIOMETRIC_UNAVAILABLE, null)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("身份签名请求")
            .setSubtitle("Engine 请求使用保险箱密钥签名")
            .setNegativeButtonText("拒绝")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // 指纹通过 → 用活动私钥签名 (P-256 签名毫秒级, 主线程可接受)
                    try {
                        val signature = privateKeyManager.signChallenge(payloadBytes)
                        val sigB64 = Base64.getEncoder().encodeToString(signature)
                        respond(true, null, sigB64)
                    } catch (e: Exception) {
                        Log.e(TAG, "Sign failed: ${e.message}")
                        respond(false, IpcErrorCode.SIGN_FAILED, null)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户拒绝 / 多次失败被系统锁定
                    respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
                }
            }
        )

        prompt.authenticate(promptInfo)
    }
}
