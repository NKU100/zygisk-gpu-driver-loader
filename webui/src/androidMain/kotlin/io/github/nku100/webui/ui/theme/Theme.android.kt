package io.github.nku100.webui.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun isSystemDarkTheme(): Boolean = isSystemInDarkTheme()

@Composable
actual fun ApplySystemBarAppearance(isDark: Boolean) {
    val context = LocalContext.current
    SideEffect {
        var current = context
        while (current is ContextWrapper && current !is Activity) {
            val base = current.baseContext
            if (base === current) return@SideEffect
            current = base
        }
        val activity = current as? Activity ?: return@SideEffect
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

@Composable
actual fun rememberHostThemeSeedColor(): Color? = null
