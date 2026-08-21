package com.sprit.tvremote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF00497F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    background = Color(0xFF0B0E12),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111418),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF272B31),
    onSurfaceVariant = Color(0xFFC3C7CF),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00629E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondaryContainer = Color(0xFFDCE3F9),
    onSecondaryContainer = Color(0xFF141C2B),
    background = Color(0xFFFAFAFD),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E8EC),
    onSurfaceVariant = Color(0xFF43474E),
)

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
