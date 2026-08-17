package com.vault

import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver

/**
 * 透明验证 Activity
 *
 * 接收 Engine 发来的 myvault://verify?session=<id> 请求:
 * 1. 从 URI 解析 sessionId
 * 2. 弹出系统指纹验证 (BiometricPrompt, BIOMETRIC_STRONG)
 * 3. 通过 myvault://callback (携带同一 sessionId) 回送结果
 * 4. 回送后立即 finish(), 不展示自有 UI
 *
 * 相比旧方案 (比对 6 位动态码字符串) 的改进:
 * - 证明因子从 "能看到 Vault 屏幕" 升级为 "持有已录入指纹的设备"
 * - 消除 6 位码被转抄/暴力猜测的攻击面 (旧入口 exported 且无速率限制)
 * - 消除动态码 30 秒过期、进程被杀即失效带来的使用混乱
 *
 * 安全约束:
 * - 全程不涉及私钥
 * - 防截屏
 */
class VerifyActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防截屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val data = intent?.data
        if (data != null && IpcContract.isVerifyUri(data)) {
            val sessionId = data.getQueryParameter(IpcContract.PARAM_SESSION)
            startBiometricVerify(sessionId)
        } else {
            // 非法 URI, 直接结束
            finish()
        }
    }

    /**
     * 弹出系统指纹验证并根据结果回送回调
     */
    private fun startBiometricVerify(sessionId: String?) {
        val ipcReceiver = IpcReceiver(this)

        fun respond(success: Boolean, errorCode: IpcErrorCode? = null) {
            ipcReceiver.sendCallback(
                IpcCallback(
                    sessionId = sessionId,
                    isSuccess = success,
                    errorCode = errorCode
                )
            )
            finish()
        }

        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // 设备无可用指纹硬件或未录入指纹
            respond(false, IpcErrorCode.BIOMETRIC_UNAVAILABLE)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Engine 身份验证")
            .setSubtitle("请验证指纹以完成登录")
            .setNegativeButtonText("取消")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    respond(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户取消或多次失败被锁定等
                    respond(false, IpcErrorCode.BIOMETRIC_FAILED)
                }
            }
        )

        prompt.authenticate(promptInfo)
    }
}
