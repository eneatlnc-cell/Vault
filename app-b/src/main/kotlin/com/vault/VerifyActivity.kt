package com.vault

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver

/**
 * 透明验证 Activity (v3, 修复: 指纹框抖动/闪退)
 *
 * 接收 Engine 发来的 myvault://verify?session=<id>&app=<pkg> 请求:
 * 1. 从 URI 解析 sessionId 与来源应用
 * 2. 弹出系统指纹验证 (BiometricPrompt, BIOMETRIC_STRONG)
 * 3. 通过 myvault://callback (携带同一 sessionId) 回送结果
 * 4. 回送后立即 finish(), 不展示自有 UI
 *
 * v3 抖动修复 (三处叠加根因):
 * 1. authenticate() 时机: 旧版在 onCreate 内直接调用, Activity 尚未 RESUMED,
 *    BiometricFragment 挂载时机错误 —— 部分机型 (MIUI/ColorOS 等) 上指纹框
 *    闪现即被系统取消 (ERROR_CANCELED), 表现为 "抖动跳过"。
 *    现改为 onResume 中展示, 保证窗口已获得焦点。
 * 2. ERROR_CANCELED 错误分类: 系统取消 (新对话框抢占/窗口切换) 不再直接回送
 *    失败, 而是自动重试一次; 仅用户主动取消/锁定等才回送失败。
 * 3. FLAG_SECURE 移除: 本页无敏感内容 (仅系统指纹对话框), 透明窗口叠加
 *    FLAG_SECURE 在部分机型与指纹 overlay 冲突导致闪烁。
 *
 * 多实例防护: manifest 配置 launchMode="singleTask", 连续唤起经 onNewIntent
 * 复用同一实例, 以最新请求为准, 不再叠加多个指纹框。
 *
 * 安全约束:
 * - 全程不涉及私钥
 * - 组件受 signature 级权限保护, 仅同证书应用可唤起
 */
class VerifyActivity : FragmentActivity() {

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE

    /** 本次生命周期内指纹框是否已展示 (防止 onResume 反复触发) */
    private var promptShown = false

    /** ERROR_CANCELED 自动重试次数 (仅一次, 防死循环) */
    private var canceledRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v3: 不再设置 FLAG_SECURE —— 页面无敏感内容, 且透明窗口 + FLAG_SECURE
        // 在部分机型与系统指纹对话框冲突导致闪烁 (抖动根因之一)
        parseRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 复用实例: 连续唤起以最新请求为准, 旧 sessionId 的回调作废
        parseRequest(intent)
        promptShown = false
        canceledRetryCount = 0
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
        // v3 修复核心: RESUMED 状态下才展示指纹框 (窗口已获得焦点)
        if (!promptShown && !isFinishing) {
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
                                // 等下一帧重新展示, 避免在错误回调栈内重入
                                window.decorView.post { showPrompt(prompt, promptInfo) }
                            } else {
                                // 连续两次系统取消: 判定环境异常, 回送失败
                                respond(false, IpcErrorCode.BIOMETRIC_FAILED)
                            }
                        }
                        // 用户主动取消 / 锁定 / 硬件异常等: 回送失败
                        else -> respond(false, IpcErrorCode.BIOMETRIC_FAILED)
                    }
                }
            }
        )

        showPrompt(prompt, promptInfo)
    }

    private fun showPrompt(prompt: BiometricPrompt, promptInfo: BiometricPrompt.PromptInfo) {
        // 仅在未销毁且已 RESUMED 时展示 (抖动修复的核心约束)
        if (!isFinishing && lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            )
        ) {
            promptShown = true
            prompt.authenticate(promptInfo)
        }
    }
}
