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
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcImportRequest
import com.vault.ipc.IpcReceiver
import com.vault.ui.screen.ScanImportScreen
import com.vault.ui.theme.VaultTheme
import com.vault.viewmodel.ScanImportViewModel

/**
 * IPC 导入入口 (v3)
 *
 * 与 MainActivity (桌面入口) 拆分的原因:
 * - android:permission 作用于整个组件; 若留在 MainActivity 上,
 *   桌面 Launcher 将因无权限而无法启动 Vault
 * - 拆分后 IPC 入口可被 signature 级权限单独保护:
 *   仅与 Vault 同证书签名的应用 (即 Engine) 可唤起, 恶意 App / 网页均不可达
 *
 * 流程:
 * 1. 解析 myvault://import?session=<id>&app=<pkg> (+ 可选 EXTRA_PAYLOAD)
 * 2. payload 非空 → 直接进入解析确认; 否则展示扫码界面
 * 3. 导入完成 (ScanImportViewModel 自动回送签名回调) → finish() 回到来源应用
 *
 * v3: 解析 URI 的 app 参数, 导入时把密钥绑定到 "该应用名下"
 * (状态页列表显示应用名, 便于区分不同接入应用)。
 */
class ImportActivity : ComponentActivity() {

    private var request by mutableStateOf<IpcImportRequest?>(null)
    private var sourceAppPackage by mutableStateOf(IpcContract.ENGINE_PACKAGE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防截屏: 密钥导入全程禁止截屏/录屏 (本页展示密钥指纹与确认信息)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        parseRequest(intent)
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
                    sourceAppPackage = sourceAppPackage,
                    onDone = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // IPC 流程中的重复唤起: 以最新请求为准 (ViewModel 状态机自身幂等保护)
        parseRequest(intent)
    }

    private fun parseRequest(intent: android.content.Intent?) {
        val receiver = IpcReceiver(this)
        request = receiver.handleIntent(intent)
        // v3: 来源应用 (URI app 参数, 默认 Engine)
        sourceAppPackage = intent?.data
            ?.getQueryParameter(IpcContract.PARAM_APP)
            ?.takeIf { it.isNotBlank() }
            ?: IpcContract.ENGINE_PACKAGE
    }
}

/**
 * 导入流程 Composable:
 * - bindSession 绑定回调会话与来源应用 (完成时自动回送签名回调)
 * - payload 非空时跳过摄像头, 直接解析来源应用移交的密钥材料
 * - onDone → finish() 返回来源应用
 */
@androidx.compose.runtime.Composable
private fun ImportFlow(
    sessionId: String,
    payload: String?,
    sourceAppPackage: String,
    onDone: () -> Unit
) {
    val vm: ScanImportViewModel = viewModel()

    LaunchedEffect(sessionId, payload, sourceAppPackage) {
        vm.bindSession(sessionId.ifBlank { null }, sourceAppPackage)
        if (payload != null) {
            vm.onQrScanned(payload)
        }
    }

    ScanImportScreen(
        viewModel = vm,
        onDone = onDone
    )
}
