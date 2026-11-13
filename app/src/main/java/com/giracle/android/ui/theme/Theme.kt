package com.giracle.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Web 版 (shadcn/ui の既定配色) に近い、白黒基調の配色
private val Light = lightColorScheme(
    primary = Color(0xFF18181B),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF52525B),
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF2563EB),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F4F5),
    surfaceContainer = Color(0xFFF7F7F8),
    surfaceContainerHigh = Color(0xFFF1F1F3),
    onSurfaceVariant = Color(0xFF71717A),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFDC2626),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFAFAFA),
    onPrimary = Color(0xFF18181B),
    primaryContainer = Color(0xFF3F3F46),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = Color(0xFFA1A1AA),
    secondaryContainer = Color(0xFF27272A),
    onSecondaryContainer = Color(0xFFFAFAFA),
    tertiary = Color(0xFF60A5FA),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF27272A),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFF87171),
)

/** 壁紙から作った色を使えるか (Android 12 以降) */
fun supportsDynamicColor(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

@Composable
fun GiracleTheme(theme: String = "system", fontScale: Float = 1f, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val scheme = when {
            dynamicColor && supportsDynamicColor() ->
                if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx)
            dark -> Dark
            else -> Light
        }
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
