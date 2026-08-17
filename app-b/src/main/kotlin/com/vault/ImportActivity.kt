package com.vault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securesocial.core.ipc.IpcImportRequest
import com.vault.ipc.IpcReceiver
import com.vault.ui.screen.ScanImportScreen
import com.vault.ui.theme.VaultTheme
import com.vault.viewmodel.ScanImportViewModel

/**
 * IPC 导入入口 (v2, 修复: 隐式 Intent 广播式投递)
 *
 * 与 MainActivity (桌面入口) 拆分的原因:
 * - android:permission 作用于整个组件; 若留在 MainActivity 上,
 *   桌面 Launcher 将因无权限而无法启动 Vault
 * - 拆分后 IPC 入口可被 signature 级权限单独保护:
 *   仅与 Vault 同证书签名的应用 (即 Engine) 可唤起, 恶意 App / 网页均不可达
 *
 * 流程:
 * 1. 解析 myvault://import?session=<id> (+ 可选 EXTRA_PAYLOAD)
 * 2. payload 非空 → 直接进入解析确认; 否则展示扫码界面
 * 3. 导入完成 (ScanImportViewModel 自动回送签名回调) → finish() 回到 Engine
 */
class ImportActivity : ComponentActivity() {

    private var request by mutableStateOf<IpcImportRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防截屏: 密钥导入全程禁止截屏/录屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        request = IpcReceiver(this).handleIntent(intent)
        if (request == null) {
            // 非 IPC 唤起 (防御): 直接结束
            finish()
            return
        }

        setContent {
            VaultTheme {
                ImportFlow(
                    sessionId = request?.sessionId.orEmpty(),
                    payload = request?.payload,
                    onDone = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // IPC 流程中的重复唤起: 以最新请求为准 (ViewModel 状态机自身幂等保护)
        request = IpcReceiver(this).handleIntent(intent)
    }
}

/**
 * 导入流程 Composable:
 * - bindSession 绑定回调会话 (完成时自动回送签名回调)
 * - payload 非空时跳过摄像头, 直接解析 Engine 移交的密钥材料
 * - onDone → finish() 返回 Engine
 */
@androidx.compose.runtime.Composable
private fun ImportFlow(sessionId: String, payload: String?, onDone: () -> Unit) {
    val vm: ScanImportViewModel = viewModel()

    LaunchedEffect(sessionId, payload) {
        vm.bindSession(sessionId.ifBlank { null })
        if (payload != null) {
            vm.onQrScanned(payload)
        }
    }

    ScanImportScreen(
        viewModel = vm,
        onDone = onDone
    )
}
