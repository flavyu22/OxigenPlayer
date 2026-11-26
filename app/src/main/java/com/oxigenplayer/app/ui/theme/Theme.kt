// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Orange80,
    secondary = Yellow80,
    tertiary = Coral80,
    background = WarmDarkBackground,
    surface = WarmDarkSurface,
    surfaceVariant = WarmDarkSurfaceVariant,
    onPrimary = Color(0xFF452B00),
    onSecondary = Color(0xFF352F00),
    onTertiary = Color(0xFF5D1900),
    onBackground = Color(0xFFF0EAE0), // Warm off-white
    onSurface = Color(0xFFF0EAE0),
    onSurfaceVariant = Color(0xFFD3C6B1) // Warmer text for variants
)

@Composable
fun OxigenPlayerTheme(
    // The player UI is intentionally dark on phones and TVs so overlay text
    // remains readable independently of the system appearance setting.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(LocalContext.current)
        }
        else -> DarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
