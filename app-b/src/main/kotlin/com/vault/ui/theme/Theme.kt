package com.vault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Material3 深色主题。
 * 保险箱应用全程使用深色主题, 无需跟随系统亮色。
 */
private val VaultDarkColorScheme = darkColorScheme(
    primary = VaultPrimary,
    onPrimary = VaultBlack,
    primaryContainer = VaultPrimaryContainer,
    onPrimaryContainer = VaultPrimary,
    secondary = VaultSecondary,
    onSecondary = VaultBlack,
    background = VaultBlack,
    onBackground = VaultOnSurface,
    surface = VaultSurface,
    onSurface = VaultOnSurface,
    surfaceVariant = VaultSurfaceVariant,
    onSurfaceVariant = VaultOnSurfaceMuted,
    error = VaultError,
    onError = VaultBlack,
    outline = VaultOutline
)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultDarkColorScheme,
        typography = VaultTypography,
        content = content
    )
}
