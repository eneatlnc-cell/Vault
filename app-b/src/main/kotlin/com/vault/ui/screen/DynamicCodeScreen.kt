package com.vault.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vault.R
import com.vault.viewmodel.DynamicCodeViewModel

/**
 * 动态码页面: 深色背景, 超大等宽居中 8 位码, 线性倒计时进度条, 过期可刷新。
 */
@Composable
fun DynamicCodeScreen(viewModel: DynamicCodeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        VaultHeader(
            title = stringResource(R.string.code_title),
            containerColor = Color(0xFF1A1A1A)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val s = state) {
                DynamicCodeViewModel.State.Idle -> IdleState(viewModel)
                DynamicCodeViewModel.State.Generating -> GeneratingState()
                is DynamicCodeViewModel.State.Showing -> ShowingState(s.code, s.progress)
                DynamicCodeViewModel.State.Expired -> ExpiredState(viewModel)
                is DynamicCodeViewModel.State.Error -> ErrorState(s.message, viewModel)
            }
        }
    }
}

@Composable
private fun IdleState(viewModel: DynamicCodeViewModel) {
    Text(
        text = stringResource(R.string.code_idle_hint),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { viewModel.generateCode(viewModel.currentTimeChallenge()) },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(stringResource(R.string.code_generate))
    }
}

@Composable
private fun GeneratingState() {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.code_generating),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ShowingState(code: String, progress: Float) {
    Text(
        text = code,
        style = MaterialTheme.typography.displayLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 56.sp,
            letterSpacing = 8.sp,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(32.dp))
    Text(
        text = stringResource(R.string.code_remaining),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(6.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
private fun ExpiredState(viewModel: DynamicCodeViewModel) {
    Text(
        text = stringResource(R.string.code_expired),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { viewModel.refresh() },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.width(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.common_refresh))
    }
}

@Composable
private fun ErrorState(message: String, viewModel: DynamicCodeViewModel) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { viewModel.refresh() },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.width(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.common_retry))
    }
}
