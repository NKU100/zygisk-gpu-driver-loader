package io.github.nku100.webui.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

@JsFun("() => window.matchMedia('(prefers-color-scheme: dark)').matches")
private external fun isBrowserDarkMode(): Boolean

@JsFun("""(changed) => {
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const listener = () => changed(media.matches);
    media.addEventListener('change', listener);
    listener();
    return {media, listener};
}""")
private external fun observeBrowserDarkMode(changed: (Boolean) -> Unit): JsAny

@JsFun("(subscription) => subscription.media.removeEventListener('change', subscription.listener)")
private external fun stopObservingBrowserDarkMode(subscription: JsAny)

@JsFun("""(changed) => {
    if (typeof window.ksu === 'undefined' || !window.ksu) return {link: null};
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = new URL('/internal/colors.css', document.baseURI).href;
    link.dataset.ksuThemeColors = 'true';
    link.onload = () => changed(getComputedStyle(document.documentElement).getPropertyValue('--primary').trim());
    link.onerror = () => changed('');
    document.head.appendChild(link);
    return {link};
}""")
private external fun observeHostThemeColors(changed: (String) -> Unit): JsAny

@JsFun("(subscription) => subscription.link && subscription.link.remove()")
private external fun stopObservingHostThemeColors(subscription: JsAny)

@Composable
actual fun isSystemDarkTheme(): Boolean {
    var isDark by remember { mutableStateOf(isBrowserDarkMode()) }
    DisposableEffect(Unit) {
        val subscription = observeBrowserDarkMode { isDark = it }
        onDispose { stopObservingBrowserDarkMode(subscription) }
    }
    return isDark
}

@Composable
actual fun ApplySystemBarAppearance(isDark: Boolean) = Unit

@Composable
actual fun rememberHostThemeSeedColor(): Color? {
    var seed by remember { mutableStateOf<Color?>(null) }
    DisposableEffect(Unit) {
        val subscription = observeHostThemeColors { cssColor ->
            seed = parseThemeColorCssValue(cssColor)
        }
        onDispose { stopObservingHostThemeColors(subscription) }
    }
    return seed
}
