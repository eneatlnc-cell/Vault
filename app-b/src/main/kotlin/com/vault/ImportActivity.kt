package com.vault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
 * 3. 确认弹窗 → BIOMETRIC_STRONG 指纹门 → 导入 (v3 新增, 修复首次绑定无指纹)
 * 4. 导入完成 (自动回送签名回调) → Success 页 → "完成" 回保险箱主界面
 *
 * v3 修复 (返回 Vault 闪退/掉回桌面):
 * - 旧版 noHistory + 独立 task, finish 后 task 为空掉回 Launcher;
 *   冷启动重建时序异常在部分机型上闪退
 * - 现改为 "完成" 按钮显式启动 MainActivity (CLEAR_TASK|NEW_TASK)
 *   回到保险箱主界面, 导航路径确定, 不依赖系统 task 回退栈
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
                    onDone = { returnToVaultHome() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // IPC 流程中的重复唤起: 以最新请求为准 (ViewModel 状态机自身幂等保护)
        parseRequest(intent)
    }

    /**
     * v3: 显式返回保险箱主界面
     *
     * CLEAR_TASK 清掉本导入 task (含自身), 用户落地在状态页,
     * 可立即看到新绑定的应用条目 —— 不再依赖系统回退栈。
     */
    private fun returnToVaultHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun parseRequest(intent: Intent?) {
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
 * - onDone → 返回保险箱主界面
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
