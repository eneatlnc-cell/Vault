package com.vault.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vault.R
import com.vault.security.SecureStorage
import com.vault.ui.components.VaultHeader
import com.vault.viewmodel.VaultStatusViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultStatusScreen(
    viewModel: VaultStatusViewModel,
    onNavigateToScan: () -> Unit,
    onNavigateToCode: () -> Unit,
    onNavigateToMigration: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次进入页面 (含从扫码导入返回) 刷新指纹列表
    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultHeader(title = stringResource(R.string.vault_title))
        },
        bottomBar = {
            BottomActions(
                onScan = onNavigateToScan,
                onCode = onNavigateToCode,
                codeEnabled = state is VaultStatusViewModel.State.Loaded
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (val s = state) {
                VaultStatusViewModel.State.Empty -> Column(modifier = Modifier.fillMaxSize()) {
                    EmptyVault(
                        modifier = Modifier.weight(1f),
                        // v3.18.0: 空 Vault (新设备) 正是迁移导入的目标场景, 提供入口
                        onMigrate = onNavigateToMigration
                    )
                }
                is VaultStatusViewModel.State.Loaded -> BindingList(
                    s.bindings,
                    // v3.18.0: 有绑定的设备 (旧设备) 可导出迁移码
                    onMigrate = onNavigateToMigration
                )
            }
        }
    }
}

@Composable
private fun EmptyVault(modifier: Modifier = Modifier, onMigrate: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.vault_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        // v3.18.0: 新设备迁移导入入口 (从旧 Vault 扫码找回绑定身份)
        OutlinedButton(onClick = onMigrate) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("从旧设备迁移绑定")
        }
    }
}

@Composable
private fun BindingList(
    bindings: List<SecureStorage.StoredBinding>,
    onMigrate: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(bindings, key = { it.appPackage }) { item ->
            BindingCard(item)
        }
        // v3.18.0: 列表尾部迁移入口 (导出迁移码 / 换机说明)
        item(key = "migration_entry") {
            Card(
                onClick = onMigrate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "迁移绑定 (换机 / 重装后转移身份)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 应用绑定卡片 (v3): 应用名 + 指纹 + 绑定时间。
 * 包名不展示 (v3 按反馈移除; 同名应用以指纹与绑定时间区分)。
 */
@Composable
private fun BindingCard(item: SecureStorage.StoredBinding) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 应用名 (大字, 状态页主标识)
            Text(
                text = item.appLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.vault_item_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatFingerprint(item.fingerprint),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.vault_item_imported_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = formatTime(item.importedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun BottomActions(
    onScan: () -> Unit,
    onCode: () -> Unit,
    codeEnabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ExtendedFloatingActionButton(
            onClick = onScan,
            icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
            text = { Text(stringResource(R.string.vault_action_scan)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.weight(1f)
        )
        ExtendedFloatingActionButton(
            onClick = onCode,
            icon = { Icon(Icons.Filled.VerifiedUser, contentDescription = null) },
            text = { Text(stringResource(R.string.vault_action_code)) },
            containerColor = if (codeEnabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "--"
    return dateFormat.format(Date(timestamp))
}
