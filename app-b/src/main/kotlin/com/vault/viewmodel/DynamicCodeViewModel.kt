package com.vault.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vault.security.DynamicCodeGenerator
import com.vault.security.PrivateKeyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 动态码 ViewModel (ECDSA 签名方案)
 *
 * 生命周期: Idle -> Generating -> Showing(code, progress) -> (30s 后) Expired -> 刷新回到 Generating。
 *
 * 说明: 动态码现为本地身份持有演示 (证明 Vault 内私钥可用),
 * 不再参与 Engine 登录验证 —— 登录已改为 VerifyActivity 的系统指纹验证。
 */
class DynamicCodeViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        object Idle : State
        object Generating : State
        data class Showing(val code: String, val progress: Float) : State
        object Expired : State
        data class Error(val message: String) : State
    }

    private val privateKeyManager = PrivateKeyManager(application)
    private val codeGenerator = DynamicCodeGenerator(privateKeyManager)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var tickerJob: Job? = null

    companion object {
        private const val VALIDITY_MILLIS = 30_000L
        private const val TICK_INTERVAL_MILLIS = 100L
    }

    /**
     * 基于时间窗口的挑战值: epochMs / 30s, 保证同一 30 秒窗口内码值一致。
     */
    fun currentTimeChallenge(): ByteArray {
        val counter = System.currentTimeMillis() / VALIDITY_MILLIS
        return counter.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 生成动态码并启动 30 秒倒计时。
     */
    fun generateCode(challenge: ByteArray) {
        _state.value = State.Generating
        viewModelScope.launch {
            try {
                val dynamicCode = codeGenerator.generateCode(challenge)
                startTicker(dynamicCode.code, dynamicCode.expiresAt)
            } catch (e: Exception) {
                _state.value = State.Error("生成失败: ${e.message ?: "无可用私钥"}")
            }
        }
    }

    /**
     * 过期后刷新: 基于当前时间窗口重新生成。
     */
    fun refresh() {
        generateCode(currentTimeChallenge())
    }

    private fun startTicker(code: String, expiresAt: Long) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val remaining = expiresAt - now
                if (remaining <= 0L) {
                    _state.value = State.Expired
                    break
                }
                val progress = (remaining.toFloat() / VALIDITY_MILLIS).coerceIn(0f, 1f)
                _state.value = State.Showing(code, progress)
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
    }
}
