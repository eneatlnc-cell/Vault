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

/**
 * 透明验证 Activity (v3.3)
 *
 * 登录流程目标交互 (用户定义):
 *   Engine 验证窗口 → 唤起本页 (透明, 无可见 UI) → 系统指纹框 →
 *   指纹通过 → 自动回送签名回调 (Engine 回到前台) → 本页 finish()
 *   全程无需用户在 Vault 侧做任何操作。
 *
 * v3.3 修复 (Vault 关闭后无法被唤起):
 * - 移除 manifest 的 noHistory: 跨应用冷启动透明 Activity 时,
 *   系统启动过渡会短暂 pause 目标页, noHistory 语义是 "不可见即销毁",
 *   部分 ROM 上表现为 Activity 刚创建就被回收, 指纹框根本没机会展示
 *   (对照: 能正常工作的 ImportActivity 没有 noHistory)。
 *   生命周期已全部由本类代码显式 finish() 控制, noHistory 纯属有害。
 * - IpcReceiver (内部初始化 PrivateKeyManager → EncryptedSharedPreferences,
 *   Keystore 主线程操作, 冷启动数百毫秒) 延迟到 指纹通过后 才构造,
 *   指纹框展示前零重初始化、零磁盘/Keystore I/O。
 * - 回送路径全程 try-catch: 任何异常都不崩溃、都保证 finish(),
 *   绝不留下透明僵尸页。
 *
 * v3.2 修复保持:
 * - onNewIntent / onResume 统一走 maybeShowPrompt() 幂等入口
 *   (修复已 RESUMED 实例重置 promptShown 后指纹框永不展示的死循环)
 * - ERROR_CANCELED 自动重试一次; RESUMED 才 authenticate; 不设 FLAG_SECURE
 */
class VerifyActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VaultVerify"
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE

    /** 本次生命周期内指纹框是否已展示 */
    private var promptShown = false

    /** ERROR_CANCELED 自动重试次数 (仅一次, 防死循环) */
    private var canceledRetryCount = 0

    /** 已回送并结束 (防重复回调) */
    private var finished = false

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
     * 统一的指纹框展示入口: 幂等可重入。
     */
    private fun maybeShowPrompt() {
        if (!promptShown && !isFinishing && !finished &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startBiometricVerify()
        }
    }

    /**
     * 弹出系统指纹验证 (轻路径: 展示前零 Keystore/磁盘 I/O)
     */
    private fun startBiometricVerify() {
        val sessionId = pendingSessionId
        if (sessionId == null) {
            finish()
            return
        }
        val appPackage = pendingAppPackage

        // 唯一的前置检查: 生物识别能力 (纯内存查询, 无 I/O)
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_UNAVAILABLE)
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
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    respond(appPackage, sessionId, true, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        // 系统原因取消 (对话框抢占/窗口失焦/锁屏): 自动重试一次
                        BiometricPrompt.ERROR_CANCELED -> {
                            if (canceledRetryCount < 1 && !finished && !isFinishing) {
                                canceledRetryCount++
                                promptShown = false
                                // 下一帧重试, 避免在错误回调栈内重入
                                window.decorView.post { maybeShowPrompt() }
                            } else {
                                respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
                            }
                        }
                        // 用户主动取消 / 锁定 / 硬件异常: 回送失败
                        else -> respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
                    }
                }
            }
        )

        promptShown = true
        prompt.authenticate(promptInfo)
    }

    /**
     * 回送验证结果并结束 (v3.3: IpcReceiver 延迟至此才构造;
     * 全程异常兜底, 任何失败都保证 finish, 绝不留下透明僵尸页)
     */
    private fun respond(
        appPackage: String,
        sessionId: String,
        success: Boolean,
        errorCode: IpcErrorCode?
    ) {
        if (finished) return
        finished = true
        try {
            IpcReceiver(this, appPackage).sendCallback(
                IpcCallback(
                    sessionId = sessionId,
                    isSuccess = success,
                    errorCode = errorCode
                )
            )
        } catch (t: Throwable) {
            // Keystore/存储异常也不可崩溃: 记录后直接结束
            Log.e(TAG, "sendCallback failed: ${t.message}")
        } finally {
            finish()
        }
    }
}
