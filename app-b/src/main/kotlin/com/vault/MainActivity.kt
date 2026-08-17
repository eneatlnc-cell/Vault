package com.vault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vault.ui.screen.DynamicCodeScreen
import com.vault.ui.screen.ScanImportScreen
import com.vault.ui.screen.VaultStatusScreen
import com.vault.ui.theme.VaultTheme
import com.vault.viewmodel.DynamicCodeViewModel
import com.vault.viewmodel.ScanImportViewModel
import com.vault.viewmodel.VaultStatusViewModel

/**
 * 桌面启动入口 (v2: IPC 入口已拆分至 ImportActivity / VerifyActivity / SignActivity)
 *
 * 拆分原因:
 * - android:permission 作用于组件级; 若 IPC intent-filter 留在此处,
 *   加权限会连桌面启动一并拦截, 不加则恶意 App 仍可唤起 —— 只能拆分
 * - 本 Activity 仅保留 LAUNCHER 入口, 不注册任何 VIEW intent-filter
 *
 * v3: 改为 FragmentActivity —— 手动扫码导入路径的确认弹窗同样需要
 * BiometricPrompt (BIOMETRIC_STRONG) 生物识别门, 与 IPC 导入路径安全一致。
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防截屏: 所有页面生效
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            VaultTheme {
                VaultNavGraph()
            }
        }
    }
}

/**
 * Vault 导航图 (v2: 纯本地导航, 不含 IPC 会话状态)
 */
@androidx.compose.runtime.Composable
private fun VaultNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "vault") {

        composable("vault") {
            val vm: VaultStatusViewModel = viewModel()
            VaultStatusScreen(
                viewModel = vm,
                onNavigateToScan = { navController.navigate("scan") },
                onNavigateToCode = { navController.navigate("code") }
            )
        }

        composable("scan") {
            val vm: ScanImportViewModel = viewModel()
            // 手动扫码导入: 无 IPC 会话 (bindSession(null)), 不回送回调
            ScanImportScreen(
                viewModel = vm,
                onDone = {
                    navController.navigate("vault") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("code") {
            val vm: DynamicCodeViewModel = viewModel()
            DynamicCodeScreen(viewModel = vm)
        }
    }
}
