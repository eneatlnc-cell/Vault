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
import com.vault.security.PrivateKeyManager
import com.vault.ui.theme.VaultTheme
import java.util.Base64

/**
 * 签名请求入口 (v3.4)
 *
 * 签名流程目标交互 (用户定义):
 *   Engine 请求签名 → 唤起本页 → 系统指纹框 → 指纹通过 →
 *   用该应用绑定私钥签名 → 自动回送结果 (Engine 回前台) → 自动 finish
 *
 * 典型用途 (Engine 侧发起):
 * - 中继注册挑战应答: Sign("RELAY-AUTH-V1" ‖ fingerprint ‖ nonce)
 * - ECDH 信令签名:    Sign("ENGINE-SIGNAL-V1" ‖ ecdhPub ‖ senderFp ‖ receiverFp)
 *
 * ★ v3.4 根因修复: 与 VerifyActivity 同步, 放弃透明无 UI 宿主,
 * 改为不透明最小 UI 页 —— 透明窗口承载 BiometricPrompt 在跨应用
 * 冷启动时极易被 ROM 取消 (ERROR_CANCELED), 是 "指纹框抖动跳过"
 * 与 "Vault 唤不起来" 的共同根源。
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

        /** 系统性取消 (ERROR_CANCELED) 的自动重试上限 */
        private const val MAX_CANCEL_RETRY = 3

        /** 重试间隔 (毫秒) */
        private const val CANCEL_RETRY_DELAY_MS = 250L
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE
    private var pendingPayload: ByteArray? = null

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
                SigningScreen()
            }
        }

        // v3.4: 用户按返回键 → 回送失败回调后再结束。
        // 旧实现直接 finish 且不回送, Engine 侧只能干等 45s 超时
        // (在途门控期间所有后续签名请求被拒)。
        onBackPressedDispatcher.addCallback(this) {
            if (!finished) {
                Log.w(TAG, "User backed out of sign request, reporting failure")
                respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
            } else {
                finish()
            }
        }

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
     * 回送签名结果并结束 (v3.4: 提升为类级方法, 返回键路径复用;
     * IpcReceiver 延迟至此构造, 展示前零 Keystore I/O; 异常兜底必然 finish)
     */
    private fun respond(
        success: Boolean,
        errorCode: IpcErrorCode? = null,
        resultBase64: String? = null
    ) {
        if (finished) return
        finished = true
        val sessionId = pendingSessionId
        if (sessionId == null) {
            finish()
            return
        }
        try {
            IpcReceiver(this, pendingAppPackage).sendCallback(
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

    /**
     * 指纹验证后执行签名
     */
    private fun startBiometricAndSign() {
        val sessionId = pendingSessionId
        val payloadBytes = pendingPayload
        if (sessionId == null) {
            finish()
            return
        }
        val appPackage = pendingAppPackage

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
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                            handleSystemCancel { respond(false, IpcErrorCode.BIOMETRIC_FAILED, null) }
                        }
                        else -> respond(false, IpcErrorCode.BIOMETRIC_FAILED, null)
                    }
                }

                override fun onAuthenticationFailed() {
                    // no-op: 系统停留在指纹框继续等待
                }
            }
        )

        promptShown = true
        prompt.authenticate(promptInfo)
    }

    /**
     * 系统性取消的重试决策 (v3.4)
     */
    private fun handleSystemCancel(giveUp: () -> Unit) {
        if (canceledRetryCount < MAX_CANCEL_RETRY && !finished && !isFinishing) {
            canceledRetryCount++
            promptShown = false
            Log.w(TAG, "Biometric canceled by system, retry $canceledRetryCount/$MAX_CANCEL_RETRY")
            window.decorView.postDelayed({ maybeShowPrompt() }, CANCEL_RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "Biometric canceled repeatedly, giving up")
            giveUp()
        }
    }
}

/**
 * 最小签名页 (不透明): 指纹框的稳定宿主。
 */
@Composable
private fun SigningScreen() {
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
                text = "签名请求",
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
