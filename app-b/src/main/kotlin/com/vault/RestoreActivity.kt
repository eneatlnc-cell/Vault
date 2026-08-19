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
import androidx.compose.material.icons.filled.Key
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
import com.vault.security.AuthGrantCache
import com.vault.security.SecureStorage
import com.vault.ui.theme.VaultTheme

/**
 * 身份恢复入口 (v3.6)
 *
 * 场景: Engine 清除数据 / 换机重装后本地绑定身份丢失, 但本 Vault 仍持有
 * 该应用专属的绑定私钥。Engine 发起 myvault://restore → 本页指纹门 →
 * 将 "该绑定的公钥 (X.509 Base64)" 经回调 result 送回 —— Engine 恢复
 * 同一 DID 身份, 私钥全程不出 Vault。
 *
 * 回送内容仅为公钥 (非秘密); 回调 sig 由 IpcReceiver 用 "被恢复的那把
 * 绑定私钥" 签出 —— Engine 用返回公钥验签即得私钥持有证明 + 公私钥自洽
 * 证明。
 *
 * 无绑定 → 直接回送 NO_BINDING 失败 (Engine 引导走新生成流程)。
 *
 * 工程模式完全复用 VerifyActivity (v3.4/3.5 验证过的稳定路径):
 * - 不透明最小 UI 承载 BiometricPrompt (透明宿主会被 ROM 取消)
 * - onNewIntent / onResume / onWindowFocusChanged 三入口幂等 maybeShowPrompt
 * - ERROR_CANCELED 递增间隔重试 (跨应用冷启动过渡期消化)
 * - 回送全程 try-catch + 必然 finish(), 绝不留僵尸页
 */
class RestoreActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VaultRestore"
        private const val MAX_CANCEL_RETRY = 3
        private val CANCEL_RETRY_DELAYS_MS = longArrayOf(400L, 900L, 1600L)
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE

    private var promptShown = false
    private var canceledRetryCount = 0
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VaultTheme {
                RestoringScreen()
            }
        }

        // 返回键 → 回送失败 (USER_CANCELLED), Engine 侧不留 20s 死等
        onBackPressedDispatcher.addCallback(this) {
            val sessionId = pendingSessionId
            if (!finished && sessionId != null) {
                respond(pendingAppPackage, sessionId, false, IpcErrorCode.USER_CANCELLED)
            } else {
                finish()
            }
        }

        parseRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseRequest(intent)
        promptShown = false
        canceledRetryCount = 0
        maybeShowPrompt()
    }

    private fun parseRequest(intent: Intent?) {
        val data = intent?.data
        if (data != null && IpcContract.isRestoreUri(data)) {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) maybeShowPrompt()
    }

    private fun maybeShowPrompt() {
        if (!promptShown && !isFinishing && !finished &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startRestore()
        }
    }

    /**
     * 恢复主流程: 前置绑定检查 (纯 EncryptedSharedPreferences 读) → 指纹门 → 回送公钥。
     */
    private fun startRestore() {
        val sessionId = pendingSessionId
        if (sessionId == null) {
            finish()
            return
        }
        val appPackage = pendingAppPackage

        // ① 前置检查: 该应用是否有可恢复的绑定
        val storage = SecureStorage(this)
        val publicKey = storage.getPublicKey(appPackage)
        val fingerprint = storage.getFingerprint(appPackage)
        if (publicKey == null || fingerprint == null) {
            Log.i(TAG, "No binding for $appPackage, reporting NO_BINDING")
            respond(appPackage, sessionId, false, IpcErrorCode.NO_BINDING)
            return
        }

        // ② 生物识别能力检查
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_UNAVAILABLE)
            return
        }

        // ③ 指纹门: 读取身份私钥前的标准授权
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("恢复身份")
            .setSubtitle("验证指纹, 将原身份归还给 ${sourceAppLabelSafe(appPackage)}")
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
                    // 授权后续 Sign 请求的静默窗口 (与登录/导入一致)
                    AuthGrantCache.grant()
                    sendRestoredIdentity(appPackage, sessionId, publicKey)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                            handleSystemCancel(appPackage, sessionId)
                        }
                        else -> respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
                    }
                }

                override fun onAuthenticationFailed() {
                    // no-op: 指纹不匹配, 系统继续留在指纹框
                }
            }
        )

        promptShown = true
        prompt.authenticate(promptInfo)
    }

    /** 来源应用显示名 (IpcReceiver 的 PackageManager 权威解析) */
    private fun sourceAppLabelSafe(pkg: String): String =
        try {
            IpcReceiver(this, pkg).sourceAppLabel
        } catch (t: Throwable) {
            pkg
        }

    private fun handleSystemCancel(appPackage: String, sessionId: String) {
        if (canceledRetryCount < MAX_CANCEL_RETRY && !finished && !isFinishing) {
            val delay = CANCEL_RETRY_DELAYS_MS[canceledRetryCount]
            canceledRetryCount++
            promptShown = false
            Log.w(TAG, "Biometric canceled by system, retry $canceledRetryCount/$MAX_CANCEL_RETRY in ${delay}ms")
            window.decorView.postDelayed({ maybeShowPrompt() }, delay)
        } else {
            Log.e(TAG, "Biometric canceled repeatedly, giving up")
            respond(appPackage, sessionId, false, IpcErrorCode.BIOMETRIC_FAILED)
        }
    }

    /**
     * 回送恢复结果: 成功时 result = Base64(X.509 公钥)。
     * IpcReceiver 自动用 "被恢复的绑定私钥" 对回调签名。
     */
    private fun sendRestoredIdentity(appPackage: String, sessionId: String, publicKey: ByteArray) {
        val pubB64 = java.util.Base64.getEncoder().encodeToString(publicKey)
        try {
            IpcReceiver(this, appPackage).sendCallback(
                IpcCallback(
                    sessionId = sessionId,
                    isSuccess = true,
                    errorCode = null
                ),
                resultBase64 = pubB64
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendRestoredIdentity failed: ${t.message}")
        } finally {
            finished = true
            finish()
        }
    }

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
 * 最小恢复页 (不透明): 指纹框的稳定宿主 (同 VerifyActivity)。
 */
@Composable
private fun RestoringScreen() {
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
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "正在恢复身份",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请按系统提示完成指纹验证\n原身份将归还给发起应用, 私钥不会离开 Vault",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            CircularProgressIndicator()
        }
    }
}
