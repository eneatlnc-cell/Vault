package com.vault.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vault.migration.MigrationManager
import com.vault.security.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 绑定迁移流程状态机 (v3.18.0)。
 *
 * 两个独立子流程:
 *
 * 导出 (旧 Vault):
 *   SelectBinding → (指纹门, UI 层) → Exporting → ShowingQR
 *
 * 导入 (新 Vault):
 *   Scanning → Parsing → Confirming → (指纹门, UI 层) → Importing → Success
 *
 * 私钥安全纪律:
 * - exportBinding 由 UI 在指纹门成功回调后调用; 解密出的明文
 *   在 MigrationManager 内序列化后立即零覆写
 * - 二维码 Bitmap 生成在 IO 线程 (载荷 ~350 字符, QR 渲染有开销)
 */
class MigrationViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        /** 入口选择: 导出 (本机) / 导入 (新机) */
        object ChooseMode : State

        // ---- 导出流程 ----
        /** 选择要导出的应用绑定 */
        data class SelectBinding(val bindings: List<SecureStorage.StoredBinding>) : State
        /** 解密 + 序列化中 (指纹门已过) */
        object Exporting : State
        /** 展示迁移二维码 (含私钥! 仅当面扫描, 界面有醒目警示) */
        data class ShowingQR(
            val appLabel: String,
            val fingerprint: String,
            val qrBitmap: Bitmap
        ) : State

        // ---- 导入流程 ----
        object Scanning : State
        object Parsing : State
        /** 展示迁移内容待确认 (含 "将替换现有绑定" 警告) */
        data class Confirming(
            val appPackage: String,
            val appLabel: String,
            val fingerprint: String,
            val willReplace: Boolean
        ) : State
        object Importing : State
        data class Success(val fingerprint: String) : State
        data class Error(val message: String) : State
    }

    private val migrationManager = MigrationManager(application)

    private val _state = MutableStateFlow<State>(State.ChooseMode)
    val state: StateFlow<State> = _state.asStateFlow()

    // 导入流程暂存的载荷原文 (确认后交 MigrationManager)
    private var pendingPayload: String? = null

    // ---- 模式与导出 ----

    fun chooseExport() {
        _state.value = State.SelectBinding(migrationManager.listBindings())
    }

    fun chooseImport() {
        pendingPayload = null
        _state.value = State.Scanning
    }

    /**
     * 指纹门成功后导出指定绑定 (UI 层门禁回调调用)。
     */
    fun exportBinding(appPackage: String, appLabel: String) {
        _state.value = State.Exporting
        viewModelScope.launch {
            try {
                val exported = migrationManager.exportBinding(appPackage)
                val bitmap = renderQr(exported.json)
                _state.value = State.ShowingQR(appLabel, exported.fingerprint, bitmap)
            } catch (e: Exception) {
                _state.value = State.Error("导出失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    fun backToSelectBinding() {
        _state.value = State.SelectBinding(migrationManager.listBindings())
    }

    fun backToChooseMode() {
        pendingPayload = null
        _state.value = State.ChooseMode
    }

    // ---- 导入流程 ----

    /**
     * 扫码命中后调用: 解析迁移载荷并进入确认页。
     */
    fun onQrScanned(payload: String) {
        if (_state.value !is State.Scanning) return
        _state.value = State.Parsing

        viewModelScope.launch {
            try {
                val data = com.vault.migration.MigrationPayload.deserialize(payload)
                    ?: run {
                        _state.value = State.Error("二维码不是有效的 Vault 迁移载荷, 请确认扫的是旧 Vault 的迁移码")
                        return@launch
                    }
                pendingPayload = payload
                val fingerprint = com.securesocial.core.crypto.KeyFingerprint
                    .compute(data.keyPair.publicKey)
                val appLabel = runCatching {
                    getApplication<Application>().packageManager
                        .getApplicationLabel(
                            getApplication<Application>().packageManager
                                .getApplicationInfo(data.appPackage, 0)
                        ).toString()
                }.getOrDefault(data.appPackage)

                _state.value = State.Confirming(
                    appPackage = data.appPackage,
                    appLabel = appLabel,
                    fingerprint = fingerprint,
                    willReplace = migrationManager.hasExistingBinding(data.appPackage)
                )
            } catch (e: Exception) {
                _state.value = State.Error("载荷解析失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 确认页通过 → 指纹门成功后调用 */
    fun confirmImport() {
        val payload = pendingPayload ?: run {
            _state.value = State.Error("无可导入的迁移数据")
            return
        }
        _state.value = State.Importing

        viewModelScope.launch {
            try {
                val result = migrationManager.importMigration(payload)
                pendingPayload = null
                _state.value = State.Success(result.fingerprint)
            } catch (e: Exception) {
                _state.value = State.Error("导入失败: ${e.message ?: "Keystore 异常"}")
            }
        }
    }

    /** 扫码未识别/取消 → 回扫描态 */
    fun resetToScanning() {
        pendingPayload = null
        _state.value = State.Scanning
    }

    /** 任意错误后回入口 */
    fun dismissError() {
        pendingPayload = null
        _state.value = State.ChooseMode
    }

    // ---- QR 渲染 ----

    private suspend fun renderQr(content: String): Bitmap = withContext(Dispatchers.IO) {
        val size = 720
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] =
                    if (matrix[x, y]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
