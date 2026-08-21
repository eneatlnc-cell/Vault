package com.vault.ui.screen

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import com.vault.security.SecureStorage
import com.vault.ui.components.VaultHeader
import com.vault.viewmodel.MigrationViewModel
import java.io.File
import java.util.concurrent.Executor

/**
 * 绑定迁移页 (v3.18.0)。
 *
 * 两个方向共用一页:
 * - 导出 (旧设备): 选择绑定 → 指纹门 → 私钥解密为迁移二维码
 * - 导入 (新设备): 拍照扫码 → 确认指纹 → 指纹门 → 落盘为新绑定
 *
 * 安全约束:
 * - 导出与导入的 "敏感动作" 前均有 BIOMETRIC_STRONG 指纹门
 *   (与 VerifyActivity / 手动扫码导入同一强度)
 * - 迁移二维码包含私钥, 展示页有醒目警示; 全局 FLAG_SECURE 防截屏
 * - 私钥明文仅指纹门后的瞬间驻留内存, 序列化即零覆写 (MigrationManager)
 */
@Composable
fun MigrationScreen(
    viewModel: MigrationViewModel,
    onDone: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            VaultHeader(title = "迁移")

            Box(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    MigrationViewModel.State.ChooseMode -> ChooseModeContent(
                        onExport = viewModel::chooseExport,
                        onImport = viewModel::chooseImport
                    )
                    is MigrationViewModel.State.SelectBinding -> SelectBindingContent(
                        bindings = s.bindings,
                        onBack = viewModel::backToChooseMode,
                        onSelect = { binding ->
                            // 导出 = 私钥出 Vault, 必须指纹门
                            withBiometricGate(context) { viewModel.exportBinding(binding.appPackage, binding.appLabel) }
                        }
                    )
                    MigrationViewModel.State.Exporting -> CenterProgress("正在解密绑定…")
                    is MigrationViewModel.State.ShowingQR -> QrShowContent(
                        state = s,
                        onBack = viewModel::backToSelectBinding
                    )
                    MigrationViewModel.State.Scanning -> ScanContent(
                        onBack = viewModel::backToChooseMode,
                        onScanned = viewModel::onQrScanned,
                        onScanFailed = viewModel::resetToScanning
                    )
                    MigrationViewModel.State.Parsing -> CenterProgress("正在解析迁移码…")
                    is MigrationViewModel.State.Confirming -> ConfirmImportContent(
                        state = s,
                        onCancel = viewModel::resetToScanning,
                        onConfirm = {
                            withBiometricGate(context) { viewModel.confirmImport() }
                        }
                    )
                    MigrationViewModel.State.Importing -> CenterProgress("正在导入绑定…")
                    is MigrationViewModel.State.Success -> SuccessContent(
                        fingerprint = s.fingerprint,
                        onDone = onDone
                    )
                    is MigrationViewModel.State.Error -> ErrorContent(
                        message = s.message,
                        onBack = viewModel::dismissError
                    )
                }
            }
        }
    }
}

// ---- 入口选择 ----

@Composable
private fun ChooseModeContent(
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "绑定迁移",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "换机或重装 Vault 后, 在旧设备导出绑定二维码,\n新设备扫码即可找回身份 (指纹门 + 光学通道, 私钥不触网)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        MigrationModeCard(
            icon = { Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(28.dp)) },
            title = "导出绑定",
            subtitle = "本机作为旧设备: 选定绑定 → 指纹验证 → 生成迁移二维码"
        ) { onExport() }
        Spacer(Modifier.height(16.dp))
        MigrationModeCard(
            icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(28.dp)) },
            title = "导入绑定",
            subtitle = "本机作为新设备: 扫旧设备的迁移码 → 确认指纹 → 导入"
        ) { onImport() }
    }
}

@Composable
private fun MigrationModeCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- 导出流程 ----

@Composable
private fun SelectBindingContent(
    bindings: List<SecureStorage.StoredBinding>,
    onBack: () -> Unit,
    onSelect: (SecureStorage.StoredBinding) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "选择要导出的绑定",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (bindings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "本机没有任何绑定可导出",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bindings, key = { it.appPackage }) { binding ->
                    Card(
                        onClick = { onSelect(binding) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = binding.appLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = binding.fingerprint,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrShowContent(
    state: MigrationViewModel.State.ShowingQR,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 醒目警示: 此码含私钥
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = "此二维码包含私钥!\n仅供新设备当面扫码, 切勿截图或转发",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Image(
                bitmap = state.qrBitmap.asImageBitmap(),
                contentDescription = "迁移二维码",
                modifier = Modifier
                    .size(280.dp)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.appLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.fingerprint,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) { Text("完成, 返回") }
    }
}

// ---- 导入流程 ----

@Composable
private fun ScanContent(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    onScanFailed: () -> Unit
) {
    val context = LocalContext.current
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val uri = photoUri
            if (uri != null) {
                val payload = decodeQrFromUri(context, uri)
                uri.path?.let { File(it).delete() }
                photoUri = null
                if (payload != null) onScanned(payload) else onScanFailed()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.QrCodeScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "扫描旧设备的迁移二维码",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "用相机拍摄旧 Vault 上展示的迁移码",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                val photoFile = File(
                    context.cacheDir,
                    "photos/migration_${System.currentTimeMillis()}.jpg"
                ).apply { parentFile?.mkdirs() }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                photoUri = uri
                takePictureLauncher.launch(uri)
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("拍照扫描")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun ConfirmImportContent(
    state: MigrationViewModel.State.Confirming,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.QrCode,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "导入以下绑定?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.appLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.fingerprint,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center
                )
            }
        }
        if (state.willReplace) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "注意: 本机已有该应用的绑定, 导入将替换旧绑定",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("取消") }
            Button(onClick = onConfirm) { Text("确认导入") }
        }
    }
}

// ---- 通用状态页 ----

@Composable
private fun CenterProgress(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SuccessContent(fingerprint: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "绑定迁移成功",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone) { Text("完成") }
    }
}

@Composable
private fun ErrorContent(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

// ---- 指纹门 (BIOMETRIC_STRONG, 与 VerifyActivity 同强度) ----

/**
 * 在敏感动作前弹出 BIOMETRIC_STRONG 指纹门, 验证通过才执行 [action]。
 *
 * 失败/取消: 不执行动作。
 * 无生物识别硬件/未录入: 降级拒绝 —— 迁移涉及私钥出/入 Vault,
 * 不允许无门禁放行 (与 IPC 导入路径一致)。
 *
 * 非 Composable: 由 onClick 等非组合上下文直接调用,
 * BiometricPrompt 仅需 FragmentActivity 宿主与主线程 Executor。
 */
private fun withBiometricGate(context: Context, action: () -> Unit) {
    val activity = context as? FragmentActivity
    if (activity == null) {
        action()
        return
    }

    val canAuth = BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // 硬件/录入缺失: 提示而非静默放行 (v3.18.0 安全决策)
        android.widget.Toast.makeText(
            context,
            "此设备未启用强生物识别, 无法执行迁移 (请先在系统设置录入指纹)",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }

    val executor: Executor = androidx.core.content.ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                action()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Vault 迁移验证")
        .setSubtitle("指纹验证后才能访问绑定私钥")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info)
}

// ---- QR 解码 (与 ScanImportScreen 同模式: 拍照 → ZXing) ----

private fun decodeQrFromUri(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (bitmap == null) return null
        val result = decodeQrFromBitmap(bitmap)
        bitmap.recycle()
        result
    } catch (e: Exception) {
        null
    }
}

private fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to
                    listOf(com.google.zxing.BarcodeFormat.QR_CODE)
            )
        )
    }
    return try {
        reader.decodeWithState(binaryBitmap).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
