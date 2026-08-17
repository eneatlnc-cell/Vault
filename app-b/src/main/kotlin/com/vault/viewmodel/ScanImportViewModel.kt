package com.vault.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vault.ipc.IpcReceiver
import com.vault.security.PrivateKeyManager
import com.securesocial.core.crypto.KeyFingerprint
import com.securesocial.core.crypto.KeyPayloadSerializer
import com.securesocial.core.ipc.IpcCallback
import com.securesocial.core.ipc.IpcErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 扫码导入流程状态机。
 *
 * 状态流转:
 *   Scanning -> Parsing -> Confirming -> Importing -> Success
 *                          |               |
 *                          v               v
 *                       (cancel)        Error
 *
 * 仅在通过 IPC 唤起 (sessionId 非空) 时回送回调。
 */
class ScanImportViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        object Scanning : State
        object Parsing : State
        data class Confirming(val fingerprint: String) : State
        object Importing : State
        data class Success(val fingerprint: String) : State
        data class Error(val message: String) : State
    }

    private val privateKeyManager = PrivateKeyManager(application)
    private var ipcReceiver: IpcReceiver = IpcReceiver(application)

    private val _state = MutableStateFlow<State>(State.Scanning)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pendingKeyData: KeyPayloadSerializer.KeyPairData? = null
    private var sessionId: String? = null
    private var sourceAppPackage: String = com.securesocial.core.ipc.IpcContract.ENGINE_PACKAGE

    /**
     * 绑定 IPC 会话标识与来源应用 (来自 myvault://import?session=..&app=..)。
     * 非空 sessionId 表示由接入应用唤起, 完成后需回送 callback。
     */
    fun bindSession(id: String?, appPackage: String) {
        sessionId = id
        sourceAppPackage = appPackage
        ipcReceiver = IpcReceiver(application, appPackage)
    }

    /**
     * 扫码命中后调用: 解析并校验二维码载荷。
     */
    fun onQrScanned(payload: String) {
        if (_state.value !is State.Scanning) return
        _state.value = State.Parsing

        viewModelScope.launch {
            val keyData = KeyPayloadSerializer.deserialize(payload)
            if (keyData == null || !KeyPayloadSerializer.validate(keyData)) {
                _state.value = State.Error("二维码格式错误或密钥不合法")
                return@launch
            }
            pendingKeyData = keyData
            val fingerprint = KeyFingerprint.compute(keyData.publicKey)
            _state.value = State.Confirming(fingerprint)
        }
    }

    /**
     * 二次确认通过: 加密存储并回送成功回调。
     */
    fun confirmImport() {
        val data = pendingKeyData ?: run {
            _state.value = State.Error("无可导入的密钥数据")
            return
        }
        _state.value = State.Importing

        viewModelScope.launch {
            try {
                // v3: 绑定到来源应用名下 (含 PackageManager 解析的显示名)
                val appLabel = ipcReceiver.sourceAppLabel
                val result = privateKeyManager.importKey(
                    keyData = data,
                    appPackage = sourceAppPackage,
                    appLabel = appLabel
                )
                pendingKeyData = null
                sendCallback(IpcCallback(sessionId = sessionId, isSuccess = true))
                _state.value = State.Success(result.fingerprint)
            } catch (e: Exception) {
                sendCallback(IpcCallback(sessionId = sessionId, isSuccess = false, errorCode = IpcErrorCode.KEYSTORE_ERROR))
                _state.value = State.Error("导入失败: ${e.message ?: "Keystore 异常"}")
            }
        }
    }

    /**
     * 用户取消: 回送 USER_CANCELLED 并回到扫描态。
     */
    fun cancel() {
        pendingKeyData = null
        sendCallback(IpcCallback(sessionId = sessionId, isSuccess = false, errorCode = IpcErrorCode.USER_CANCELLED))
        _state.value = State.Scanning
    }

    /**
     * 错误后重试: 回到扫描态。
     */
    fun resetToScanning() {
        pendingKeyData = null
        _state.value = State.Scanning
    }

    private fun sendCallback(callback: IpcCallback) {
        // 仅在 IPC 唤起场景下回送, 避免独立启动时抛 ActivityNotFoundException
        if (sessionId != null) {
            ipcReceiver.sendCallback(callback)
        }
    }
}
