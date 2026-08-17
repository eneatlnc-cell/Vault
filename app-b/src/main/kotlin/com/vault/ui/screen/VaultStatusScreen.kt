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
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.vault.viewmodel.VaultStatusViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultStatusScreen(
    viewModel: VaultStatusViewModel,
    onNavigateToScan: () -> Unit,
    onNavigateToCode: () -> Unit
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
                VaultStatusViewModel.State.Empty -> EmptyVault()
                is VaultStatusViewModel.State.Loaded -> BindingList(s.bindings)
            }
        }
    }
}

@Composable
private fun EmptyVault() {
    Column(
        modifier = Modifier
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
    }
}

@Composable
private fun BindingList(bindings: List<SecureStorage.StoredBinding>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(bindings, key = { it.appPackage }) { item ->
            BindingCard(item)
        }
    }
}

/**
 * 应用绑定卡片 (v3): 应用名 + 包名 + 指纹 + 绑定时间。
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
            // 包名 (小字, 区分同名应用)
            if (item.appLabel != item.appPackage) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.appPackage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

/**
 * 指纹展示: 前 8 位 ... 后 8 位。
 */
internal fun formatFingerprint(fingerprint: String): String {
    if (fingerprint.length <= 16) return fingerprint
    return fingerprint.take(8) + "..." + fingerprint.takeLast(8)
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "--"
    return dateFormat.format(Date(timestamp))
}
