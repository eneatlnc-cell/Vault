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
import androidx.compose.runtime.mutableStateOf
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

        /**
         * 重试间隔递增表 (v3.5): 消化跨应用冷启动过渡期, 见 VerifyActivity
         */
        private val CANCEL_RETRY_DELAYS_MS = longArrayOf(400L, 900L, 1600L)
    }

    private var pendingSessionId: String? = null
    private var pendingAppPackage: String = IpcContract.ENGINE_PACKAGE
    private var pendingPayload: ByteArray? = null

    /** v3.5.1: 待签载荷摘要 (审计修复: 盲签)。非空时签名页展示 */
    private var payloadDigest = mutableStateOf<Pair<String, String>?>(null)

    /** 本次生命周期内指纹框是否已发起 */
    private var promptShown = false

    /** ERROR_CANCELED 已重试次数 */
    private var canceledRetryCount = 0

    /** 已回送并结束 (防重复回调) */
    private var finished = false

    /** 静默签名模式 (授权窗口内, 不弹指纹框) */
    private var silentMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v3.17 防截屏: 本页展示待签载荷摘要 (域前缀 + SHA-256), 属敏感材料;
        // 现代 BiometricPrompt 为系统层 Overlay, 不受宿主窗口 FLAG_SECURE 影响
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        // 不透明最小 UI: 指纹框的稳定宿主 (v3.4)
        setContent {
            VaultTheme {
                SigningScreen(
                    silent = silentMode.value,
                    payloadDigest = payloadDigest.value
                )
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
     *
     * v3.17: 待签字节优先读 Intent Extra (EXTRA_PAYLOAD), URI 查询参数
     * 仅作旧版 Engine 兼容回退 —— Intent data 会随 ActivityTaskManager
     * 的 "START u0" 日志行进 logcat, 挑战 nonce/ECDH 公钥不得进 URI。
     */
    private fun parseAndValidate(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        val sessionId = uri.getQueryParameter(IpcContract.PARAM_SESSION) ?: return false

        // v3.17: Extra 优先, URI 回退
        val payloadB64 = intent.getStringExtra(IpcContract.EXTRA_PAYLOAD)
            ?: uri.getQueryParameter(IpcContract.PARAM_PAYLOAD)
            ?: return false

        pendingSessionId = sessionId
        pendingAppPackage = uri.getQueryParameter(IpcContract.PARAM_APP)
            ?.takeIf { it.isNotBlank() }
            ?: IpcContract.ENGINE_PACKAGE

        pendingPayload = try {
            Base64.getDecoder().decode(payloadB64)
        } catch (e: IllegalArgumentException) {
            null
        }
        payloadDigest.value = pendingPayload?.let { describePayload(it) }
        return true
    }

    /**
     * v3.5.1: 载荷人读摘要 (审计修复: 盲签)
     *
     * 原实现签名确认页对载荷零展示 —— Engine 被入侵后可让用户盲签任意内容
     * (如伪造 ECDH 信令实施 MITM)。展示两要素供人工比对:
     * 1. 域前缀: 载荷开头的可打印 ASCII 段 (协议域分隔符, 如 RELAY-AUTH-V1)
     * 2. SHA-256 摘要前 16 hex: 载荷全量绑定, 与 Engine 侧展示值比对可发现篡改
     */
    private fun describePayload(bytes: ByteArray): Pair<String, String> {
        val printablePrefix = buildString {
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E && length < 24) append(c.toChar()) else break
            }
        }.ifBlank { "(binary)" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return printablePrefix to digest
    }

    override fun onResume() {
        super.onResume()
        maybeShowPrompt()
    }

    /**
     * 窗口真正获得焦点后才首次弹指纹框 (v3.5), 见 VerifyActivity
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            maybeShowPrompt()
        }
    }

    /**
     * 统一的指纹框展示入口: 幂等可重入。
     * v3.5: 授权窗口内 (30s 内刚完成过生物识别) 走静默签名, 不弹指纹框。
     */
    private fun maybeShowPrompt() {
        if (promptShown || isFinishing || finished) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        if (AuthGrantCache.isRecentlyGranted()) {
            promptShown = true  // 占位防重入 (静默路径同样只执行一次)
            silentMode.value = true
            Log.d(TAG, "Silent signing: biometric granted recently")
            startSilentSign()
        } else {
            startBiometricAndSign()
        }
    }

    /**
     * 静默签名 (v3.5): 授权窗口内直接签名, 不再弹第二个指纹框。
     * 消除 "登录指纹 → 挑战签名指纹" 连续两次验证的死循环体验。
     */
    private fun startSilentSign() {
        val payloadBytes = pendingPayload ?: run {
            respond(false, IpcErrorCode.SIGN_FAILED)
            return
        }
        try {
            val manager = PrivateKeyManager(this)
            if (!manager.hasStoredKey(pendingAppPackage)) {
                respond(false, IpcErrorCode.NO_KEY_BOUND)
                return
            }
            val signature = manager.signChallenge(payloadBytes, pendingAppPackage)
            val sigB64 = Base64.getEncoder().encodeToString(signature)
            respond(true, null, sigB64)
        } catch (t: Throwable) {
            Log.e(TAG, "Silent sign failed: ${t.message}")
            respond(false, IpcErrorCode.SIGN_FAILED, null)
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
                    // v3.5: 指纹通过 → 授权后续 Sign 请求的静默窗口
                    AuthGrantCache.grant()
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
     * 系统性取消的重试决策 (v3.5: 递增间隔)
     */
    private fun handleSystemCancel(giveUp: () -> Unit) {
        if (canceledRetryCount < MAX_CANCEL_RETRY && !finished && !isFinishing) {
            val delay = CANCEL_RETRY_DELAYS_MS[canceledRetryCount]
            canceledRetryCount++
            promptShown = false
            Log.w(TAG, "Biometric canceled by system, retry $canceledRetryCount/$MAX_CANCEL_RETRY in ${delay}ms")
            window.decorView.postDelayed({ maybeShowPrompt() }, delay)
        } else {
            Log.e(TAG, "Biometric canceled repeatedly, giving up")
            giveUp()
        }
    }
}

/**
 * 最小签名页 (不透明): 指纹框的稳定宿主。
 *
 * @param silent v3.5: 静默签名模式 (授权窗口内, 无指纹框), 文案相应切换
 * @param payloadDigest v3.5.1: (域前缀, SHA-256 前 16 hex); 盲签修复 ——
 *        用户验证指纹前可核对待签内容, 与 Engine 侧展示值比对可发现载荷篡改
 */
@Composable
private fun SigningScreen(silent: Boolean, payloadDigest: Pair<String, String>? = null) {
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
                text = if (silent) "正在签名" else "签名请求",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (silent) "正在使用保险箱密钥完成签名\n完成后将自动返回应用"
                else "请按系统提示完成指纹验证\n完成后将自动返回应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // v3.5.1: 待签载荷摘要 (盲签修复)
            if (payloadDigest != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "待签内容: ${payloadDigest.first}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SHA-256: ${payloadDigest.second}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
