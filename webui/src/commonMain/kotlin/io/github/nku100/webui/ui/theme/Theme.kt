package io.github.nku100.webui.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme

/**
 * Theme mode for the app.
 */
enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Platform-specific: detect whether the system is in dark mode.
 * - Android: delegates to isSystemInDarkTheme()
 * - wasmJs: observes the browser color-scheme media query.
 */
@Composable
expect fun isSystemDarkTheme(): Boolean

/**
 * Shared Miuix theme with optional system dynamic colors.
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode,
    enableMonet: Boolean = false,
    pageScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val defaultDarkColors = darkColorScheme()
    val controller = ThemeController(
        colorSchemeMode = resolveColorSchemeMode(isDark, enableMonet),
        darkColors = darkColorScheme(surfaceContainerHigh = defaultDarkColors.surfaceContainerHighest),
        keyColor = rememberHostThemeSeedColor().takeIf { enableMonet },
        isDark = isDark,
    )
    val density = LocalDensity.current
    val scale = normalizePageScale(pageScale)

    ApplySystemBarAppearance(isDark)

    MiuixTheme(
        controller = controller,
        content = {
            val colorScheme = MiuixTheme.colorScheme
            CompositionLocalProvider(
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
                LocalReleaseCardSurface provides resolveReleaseCardSurface(
                    isDark = isDark,
                    enableMonet = enableMonet,
                    surface = colorScheme.surface,
                    surfaceContainerHigh = colorScheme.surfaceContainerHigh,
                ),
                LocalPageScale provides scale,
                LocalDensity provides Density(density.density * scale, density.fontScale),
            ) {
                content()
            }
        }
    )
}

internal fun resolveColorSchemeMode(isDark: Boolean, enableMonet: Boolean): ColorSchemeMode =
    when {
        enableMonet && isDark -> ColorSchemeMode.MonetDark
        enableMonet -> ColorSchemeMode.MonetLight
        isDark -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.Light
    }

internal fun resolveReleaseCardSurface(
    isDark: Boolean,
    enableMonet: Boolean,
    surface: Color,
    surfaceContainerHigh: Color,
): Color = if (!isDark && !enableMonet) surface else surfaceContainerHigh

@Composable
expect fun ApplySystemBarAppearance(isDark: Boolean)

@Composable
expect fun rememberHostThemeSeedColor(): Color?

internal val LocalPageScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }
internal val LocalReleaseCardSurface = staticCompositionLocalOf { Color.White }

internal fun normalizePageScale(scale: Float): Float =
    if (scale.isFinite()) scale.coerceIn(0.8f, 1.1f) else 1f

internal fun parseThemeColorCssValue(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any {
            it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
        }
    ) return null
    return Color(0xFF000000L or hex.toLong(16))
}
