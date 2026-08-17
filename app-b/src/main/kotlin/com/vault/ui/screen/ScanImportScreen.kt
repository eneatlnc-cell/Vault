package com.vault.ui.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import com.vault.R
import com.vault.ui.components.VaultHeader
import com.vault.viewmodel.ScanImportViewModel
import java.io.File

@Composable
fun ScanImportScreen(
    viewModel: ScanImportViewModel,
    onDone: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 拍照用的临时文件 URI
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var showCameraDialog by remember { mutableStateOf(false) }

    // 拍照 Launcher: 通过系统相机 Intent 拍照, 不需要 CAMERA 权限
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            // 拍照成功, 解码二维码
            val uri = photoUri
            if (uri != null) {
                val payload = decodeQrFromUri(context, uri)
                // 删除临时文件
                uri.path?.let { File(it).delete() }
                photoUri = null

                if (payload != null) {
                    viewModel.onQrScanned(payload)
                } else {
                    // 未识别到二维码, 回到扫描态让用户重试
                    viewModel.resetToScanning()
                }
            }
        }
    }

    // 二次确认弹窗局部状态
    var showFinalDialog by remember { mutableStateOf(false) }
    val pendingFingerprint = (state as? ScanImportViewModel.State.Confirming)?.fingerprint

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部标题
        VaultHeader(
            title = stringResource(R.string.scan_title),
            containerColor = MaterialTheme.colorScheme.surface
        )

        // 状态驱动的浮层
        ScanContent(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onTakePhoto = {
                // 创建临时文件并通过 FileProvider 获取 URI
                val photoFile = File(
                    context.cacheDir,
                    "photos/qr_${System.currentTimeMillis()}.jpg"
                ).apply { parentFile?.mkdirs() }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                photoUri = uri
                takePictureLauncher.launch(uri)
            },
            onConfirmRequest = { showFinalDialog = true },
            onCancel = viewModel::cancel,
            onRetry = viewModel::resetToScanning,
            onDone = onDone
        )
    }

    // 二次确认弹窗
    if (showFinalDialog && pendingFingerprint != null) {
        AlertDialog(
            onDismissRequest = { showFinalDialog = false },
            title = { Text(stringResource(R.string.scan_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.scan_confirm_subtitle),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = formatFingerprint(pendingFingerprint),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.scan_double_confirm_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = stringResource(R.string.scan_replace_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinalDialog = false
                        // v3: 确认导入前必须通过生物识别 (BIOMETRIC_STRONG)
                        requestBiometricThenImport(context, viewModel)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.scan_confirm_button))
                }
            },
            dismissButton = {
                Button(
                    onClick = { showFinalDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun ScanContent(
    state: ScanImportViewModel.State,
    modifier: Modifier = Modifier,
    onTakePhoto: () -> Unit,
    onConfirmRequest: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    when (state) {
        ScanImportViewModel.State.Scanning -> {
            // 扫描入口: 提示用户拍照
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.scan_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = onTakePhoto,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 32.dp,
                            vertical = 14.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.scan_take_photo), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        ScanImportViewModel.State.Parsing -> {
            ProgressOverlay(
                modifier = modifier,
                message = stringResource(R.string.scan_parsing)
            )
        }

        ScanImportViewModel.State.Importing -> {
            ProgressOverlay(
                modifier = modifier,
                message = stringResource(R.string.scan_importing)
            )
        }

        is ScanImportViewModel.State.Confirming -> {
            // 底部居中 "确认导入" 按钮
            Box(
                modifier = modifier,
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.scan_confirm_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatFingerprint(state.fingerprint),
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Button(
                            onClick = onConfirmRequest,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(R.string.scan_confirm_button))
                        }
                        Button(
                            onClick = onCancel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            }
        }

        is ScanImportViewModel.State.Success -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.scan_success_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatFingerprint(state.fingerprint),
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.scan_success_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onDone,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(R.string.common_done))
                        }
                    }
                }
            }
        }

        is ScanImportViewModel.State.Error -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressOverlay(modifier: Modifier, message: String) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

// ---- 生物识别门 (v3) ----

/**
 * 确认导入前的强制生物识别门 (修复: 首次绑定无指纹输入环节)
 *
 * 安全语义: 导入 = 用新密钥覆盖旧身份, 属不可逆高危操作。
 * 仅凭 "拿到已解锁的手机" 不应足以完成绑定 —— 必须再过一道
 * BIOMETRIC_STRONG 指纹验证 (TEE 级, 无法被后台模拟)。
 *
 * 降级策略: 宿主非 FragmentActivity 或设备无强指纹能力时,
 * 保留原有二次确认弹窗直接导入 (老设备兼容), 安全性由
 * signature 权限门禁 + 确认弹窗兜底。
 *
 * @param context  宿主 Activity context (ImportActivity / MainActivity, 均为 FragmentActivity)
 * @param viewModel 扫码导入状态机 (指纹通过后调用 confirmImport)
 */
private fun requestBiometricThenImport(
    context: Context,
    viewModel: ScanImportViewModel
) {
    val activity = context as? androidx.fragment.app.FragmentActivity
    if (activity == null) {
        viewModel.confirmImport()
        return
    }

    val biometricManager = androidx.biometric.BiometricManager.from(context)
    if (biometricManager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    ) {
        // 无强指纹硬件/未录入: 降级直接导入
        viewModel.confirmImport()
        return
    }

    val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
        .setTitle("确认导入密钥")
        .setSubtitle("验证指纹以将密钥绑定至保险箱")
        .setNegativeButtonText("取消")
        .setAllowedAuthenticators(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        .build()

    val prompt = androidx.biometric.BiometricPrompt(
        activity,
        androidx.core.content.ContextCompat.getMainExecutor(activity),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: androidx.biometric.BiometricPrompt.AuthenticationResult
            ) {
                // 指纹通过 → 执行导入
                viewModel.confirmImport()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 用户取消/多次失败: 停留在确认页 (State.Confirming), 不导入
            }
        }
    )
    prompt.authenticate(promptInfo)
}

// ---- QR 解码工具函数 ----

/**
 * 从图片 URI 读取 Bitmap 并尝试解码 QR 码。
 *
 * 使用 ZXing MultiFormatReader, 纯本地解码, 无网络。
 * 返回 QR 码的原始字符串内容; 未识别到返回 null。
 */
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

/**
 * 从 Bitmap 解码 QR 码。
 *
 * 将 Bitmap 转为 RGB 像素数组, 经 RGBLuminanceSource → HybridBinarizer → BinaryBitmap,
 * 交由 ZXing MultiFormatReader 解码。
 */
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
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}

/**
 * 指纹展示: 前 8 位 ... 后 8 位。
 */
internal fun formatFingerprint(fingerprint: String): String {
    if (fingerprint.length <= 16) return fingerprint
    return fingerprint.take(8) + "..." + fingerprint.takeLast(8)
}
