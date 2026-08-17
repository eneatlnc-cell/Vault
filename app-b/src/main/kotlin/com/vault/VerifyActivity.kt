package com.vault

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcErrorCode
import com.vault.ipc.IpcReceiver
import com.vault.ui.theme.VaultTheme

/**
 * 指纹验证入口 (v3.4)
 *
 * 登录流程目标交互 (用户定义):
 *   Engine 验证窗口 → 唤起本页 → 系统指纹框 → 指纹通过 →
 *   自动回送签名回调 (Engine 回前台) → 本页自动 finish()
 *
 * ★ v3.4 根因修复: 放弃 "透明无 UI Activity" 承载方案。
 * 透明窗口在跨应用冷启动期间极易被 ROM 判定焦点丢失/不可见,
 * BiometricPrompt 随即收到 ERROR_CANCELED —— 这正是最初
 * "指纹框抖动跳过" 与后来 "Vault 唤不起来" 的共同根源。
 * 绑定流程的指纹门 (ImportActivity, 不透明 UI) 一直稳定, 证明
 * 不透明宿主才是可靠路径。现对齐为: 不透明最小 UI 页
 * (图标 + "正在验证身份" + 加载圈), 指纹框稳定浮于其上。
 *
 * 稳定性设计:
 * - ERROR_CANCELED 重试 3 次 (间隔 250ms): 消化冷启动过渡期的
 *   系统性取消; 仅用户主动取消 (否定按钮/锁定) 才回送失败
 * - onNewIntent / onResume 统一走 maybeShowPrompt() 幂等入口
 * - 回送全程 try-catch + 必然 finish(), 绝不留僵尸页
 * - IpcReceiver (Keystore I/O) 延迟到回送时才构造
 */
class VerifyActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VaultVerify"

        /** 系统性取消 (ERROR_CANCELED) 的自动重试上限 */
        private const val MAX_CANCEL_RETRY = 3

        /** 重试间隔 (毫秒) */
        private const val CANCEL_RETRY_DELAY_MS = 250L
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE

    /** 本次生命周期内指纹框是否已发起 */
    private var promptShown = false

    /** ERROR_CANCELED 已重试次数 */
    private var canceledRetryCount = 0

    /** 已回送并结束 (防重复回调) */
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 不透明最小 UI: 指纹框的稳定宿主 (v3.4)
        setContent {
            VaultTheme {
                VerifyingScreen()
            }
        }

        // v3.4: 用户按返回键 → 回送失败回调后再结束。
        // 旧实现直接 finish 且不回送, Engine 登录页只能干等 20s 超时。
        onBackPressedDispatcher.addCallback(this) {
            val sessionId = pendingSessionId
            if (!finished && sessionId != null) {
                Log.w(TAG, "User backed out of verification, reporting failure")
                respond(pendingAppPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
            } else {
                finish()
            }
        }

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
            maybeShowPrompt()
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

        // 唯一前置检查: 生物识别能力 (纯内存查询)
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
                        // 系统性取消 (窗口过渡/焦点被抢/新对话框覆盖):
                        // 延迟重试, 最多 MAX_CANCEL_RETRY 次
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                            handleSystemCancel(appPackage, sessionId)
                        }
                        // 用户主动取消 / 多次失败锁定 / 硬件忙: 回送失败
                        else -> respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
                    }
                }

                // 指纹不匹配: 系统会继续留在指纹框, 无需干预
                override fun onAuthenticationFailed() {
                    // no-op
                }
            }
        )

        promptShown = true
        prompt.authenticate(promptInfo)
    }

    /**
     * 系统性取消的重试决策 (v3.4)
     */
    private fun handleSystemCancel(appPackage: String, sessionId: String) {
        if (canceledRetryCount < MAX_CANCEL_RETRY && !finished && !isFinishing) {
            canceledRetryCount++
            promptShown = false
            Log.w(TAG, "Biometric canceled by system, retry $canceledRetryCount/$MAX_CANCEL_RETRY")
            window.decorView.postDelayed({ maybeShowPrompt() }, CANCEL_RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "Biometric canceled repeatedly, giving up")
            respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
        }
    }

    /**
     * 回送验证结果并结束 (IpcReceiver 延迟至此构造; 异常兜底必然 finish)
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
            Log.e(TAG, "sendCallback failed: ${t.message}")
        } finally {
            finish()
        }
    }
}

/**
 * 最小验证页 (不透明): 指纹框的稳定宿主。
 * 页面本身无敏感信息, 不设 FLAG_SECURE (避免与系统指纹层冲突)。
 */
@Composable
private fun VerifyingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "正在验证身份",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请按系统提示完成指纹验证\n完成后将自动返回应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
