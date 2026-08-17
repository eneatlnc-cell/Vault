package com.vault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securesocial.core.ipc.IpcContract
import com.securesocial.core.ipc.IpcImportRequest
import com.vault.ipc.IpcReceiver
import com.vault.ui.screen.ScanImportScreen
import com.vault.ui.theme.VaultTheme
import com.vault.viewmodel.ScanImportViewModel

/**
 * IPC 导入入口 (v3.2)
 *
 * 绑定流程目标交互 (用户定义):
 *   Engine 生成密钥对 → 唤起本页导入 → 二次确认弹窗 → 指纹门 →
 *   导入落库 → 自动回送签名回调 (Engine 回到前台) → 本页自动 finish。
 *   ★ 关键步骤: 导入成功后 Vault 自动确认绑定并立即跳回 Engine,
 *     不停在成功页等待用户操作, 也不落在 Vault 自己的主界面。
 *
 * 手动扫码路径 (Vault 桌面进入, 无 IPC 会话): 保留成功页与 "完成"
 * 按钮, 返回保险箱状态页。
 *
 * 与 MainActivity (桌面入口) 拆分的原因:
 * - android:permission 作用于整个组件; 若留在 MainActivity 上,
 *   桌面 Launcher 将因无权限而无法启动 Vault
 * - 拆分后 IPC 入口可被 signature 级权限单独保护:
 *   仅与 Vault 同证书签名的应用 (即 Engine) 可唤起, 恶意 App / 网页均不可达
 */
class ImportActivity : androidx.fragment.app.FragmentActivity() {

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
                    onDone = {
                        // IPC 路径: 回调已把 Engine 拉回前台, finish 即落在 Engine
                        // (本 Activity 为 Vault task 根, 结束后回到启动方任务)
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // IPC 流程中的重复唤起: 以最新请求为准 (ViewModel 状态机自身幂等保护)
        parseRequest(intent)
    }

    private fun parseRequest(intent: Intent?) {
        val receiver = IpcReceiver(this)
        request = receiver.handleIntent(intent)
        // 来源应用 (URI app 参数, 默认 Engine)
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
 * - ★ IPC 场景 (sessionId 非空): 导入成功瞬间自动 onDone 跳回 Engine,
 *   成功页一闪而过 (甚至不渲染); 手动扫码场景保留成功页人工确认
 */
@androidx.compose.runtime.Composable
private fun ImportFlow(
    sessionId: String,
    payload: String?,
    sourceAppPackage: String,
    onDone: () -> Unit
) {
    val vm: ScanImportViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId, payload, sourceAppPackage) {
        vm.bindSession(sessionId.ifBlank { null }, sourceAppPackage)
        if (payload != null) {
            vm.onQrScanned(payload)
        }
    }

    // v3.2 关键步骤: IPC 导入成功 → 立即跳回发起方 (Engine)
    LaunchedEffect(state, sessionId) {
        if (state is ScanImportViewModel.State.Success && sessionId.isNotBlank()) {
            onDone()
        }
    }

    ScanImportScreen(
        viewModel = vm,
        onDone = onDone
    )
}
