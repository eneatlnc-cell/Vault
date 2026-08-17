package com.vault

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver

/**
 * 透明验证 Activity (v3.2)
 *
 * 登录流程目标交互 (用户定义):
 *   Engine 验证窗口 → 唤起本页 (透明, 无可见 UI) → 系统指纹框 →
 *   指纹通过 → 自动回送签名回调 (Engine 回到前台) → 本页 finish()
 *   全程无需用户在 Vault 侧做任何操作, 无中间停留页面。
 *
 * v3.2 修复 (指纹框不弹/卡 Loading 死循环):
 * - 旧版 onNewIntent 只重置 promptShown, 但 Activity 已 RESUMED 时
 *   onResume 不再触发 → 指纹框永不展示 → Engine 卡 Loading,
 *   用户重试又走 onNewIntent, 表现为 "绑定陷入循环"。
 *   现 onNewIntent / onResume 统一走 maybeShowPrompt()。
 *
 * v3 修复保持:
 * - authenticate() 时机: onResume (RESUMED 状态) 才展示
 * - ERROR_CANCELED 自动重试一次
 * - 不设 FLAG_SECURE (与指纹 overlay 冲突)
 *
 * 稳定性: manifest 锁定 portrait, 防透明页 config change 重建
 * 导致指纹框闪断 (抖动)。
 */
class VerifyActivity : FragmentActivity() {

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE

    /** 本次生命周期内指纹框是否已展示 */
    private var promptShown = false

    /** ERROR_CANCELED 自动重试次数 (仅一次, 防死循环) */
    private var canceledRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parseRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 复用实例: 以最新请求为准 (旧 sessionId 作废)
        parseRequest(intent)
        promptShown = false
        canceledRetryCount = 0
        // v3.2 关键修复: 已 RESUMED 时 onResume 不会再触发, 必须在此主动展示
        maybeShowPrompt()
    }

    private fun parseRequest(intent: Intent?) {
        val data = intent?.data
        if (data != null && IpcContract.isVerifyUri(data)) {
            pendingSessionId = data.getQueryParameter(IpcContract.PARAM_SESSION)
            pendingAppPackage = data.getQueryParameter(IpcContract.PARAM_APP)
                ?.takeIf { it.isNotBlank() }
                ?: IpcContract.ENGINE_PACKAGE
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        maybeShowPrompt()
    }

    /**
     * 统一的指纹框展示入口 (v3.2):
     * 仅在 未展示过 + 未销毁 + 已 RESUMED 时展示, 幂等可重入。
     */
    private fun maybeShowPrompt() {
        if (!promptShown && !isFinishing &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startBiometricVerify()
        }
    }

    /**
     * 弹出系统指纹验证并根据结果回送回调
     */
    private fun startBiometricVerify() {
        val sessionId = pendingSessionId
        val appPackage = pendingAppPackage
        val ipcReceiver = IpcReceiver(this, appPackage)

        fun respond(success: Boolean, errorCode: IpcErrorCode? = null) {
            // 回调显式拉起发起方 (Engine), Engine 任务回到前台
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
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            respond(false, IpcErrorCode.BIOMETRIC_UNAVAILABLE)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("身份验证")
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
                    when (errorCode) {
                        // 系统原因取消 (对话框抢占/窗口失焦/锁屏): 自动重试一次
                        BiometricPrompt.ERROR_CANCELED -> {
                            if (canceledRetryCount < 1) {
                                canceledRetryCount++
                                promptShown = false
                                // 下一帧重试, 避免在错误回调栈内重入
                                window.decorView.post { maybeShowPrompt() }
                            } else {
                                respond(false, IpcErrorCode.BIOMETRIC_FAILED)
                            }
                        }
                        // 用户主动取消 / 锁定 / 硬件异常: 回送失败
                        else -> respond(false, IpcErrorCode.BIOMETRIC_FAILED)
                    }
                }
            }
        )

        promptShown = true
        prompt.authenticate(promptInfo)
    }
}
