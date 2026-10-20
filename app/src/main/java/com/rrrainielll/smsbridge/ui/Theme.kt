package com.rrrainielll.smsbridge.ui

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF546E7A),
    tertiary = Color(0xFF2E7D32)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFF81C784)
)

/** Semantic status colours that stay readable on both themes. */
object StatusColors {
    val ok @Composable get() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val bad @Composable get() = MaterialTheme.colorScheme.error
}

@Composable
fun SmsBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
