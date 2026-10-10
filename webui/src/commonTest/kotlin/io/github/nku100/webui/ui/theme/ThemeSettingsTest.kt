package io.github.nku100.webui.ui.theme

import io.github.nku100.webui.data.ModuleConfig
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class ThemeSettingsTest {
    @Test
    fun monetOffUsesDefaultMiuixPaletteInBothAppearances() {
        assertEquals(ColorSchemeMode.Light, resolveColorSchemeMode(false, false))
        assertEquals(ColorSchemeMode.Dark, resolveColorSchemeMode(true, false))
    }

    @Test
    fun monetOnUsesDynamicPaletteInBothAppearances() {
        assertEquals(ColorSchemeMode.MonetLight, resolveColorSchemeMode(false, true))
        assertEquals(ColorSchemeMode.MonetDark, resolveColorSchemeMode(true, true))
    }

    @Test
    fun legacyColorStyleIsReadButNoLongerWritten() {
        val json = Json { ignoreUnknownKeys = true }
        val config = json.decodeFromString<ModuleConfig>("""{"colorStyle":"TEAL","themeMode":"DARK"}""")
        assertEquals("DARK", config.themeMode)
        assertFalse(json.encodeToString(ModuleConfig.serializer(), config).contains("colorStyle"))
    }

    @Test
    fun hostColorCssAcceptsOnlyOpaqueHexColors() {
        assertEquals(Color(0xFF6750A4), parseThemeColorCssValue(" #6750A4 "))
        assertNull(parseThemeColorCssValue("rgb(103, 80, 164)"))
        assertNull(parseThemeColorCssValue("#1234"))
    }

    @Test
    fun pageScaleIsBoundedAndInvalidValuesUseDefault() {
        assertEquals(0.8f, normalizePageScale(0.5f))
        assertEquals(1.1f, normalizePageScale(1.5f))
        assertEquals(1f, normalizePageScale(Float.NaN))
    }

    @Test
    fun hostThemeSettingsRoundTripThroughModuleConfigJson() {
        val json = Json { encodeDefaults = true }
        val config = ModuleConfig(
            enableMonet = false,
            pageScale = 1.1f,
            enableNavigationBadge = false,
        )
        val decoded = json.decodeFromString<ModuleConfig>(
            json.encodeToString(ModuleConfig.serializer(), config),
        )
        assertEquals(false, decoded.enableMonet)
        assertEquals(1.1f, decoded.pageScale)
        assertFalse(decoded.enableNavigationBadge)
    }
}
