package io.github.nku100.webui.ui.screen.settings

import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeSettingsUiStateTest {
    @Test
    fun sliderTapMapsTrackPositionToPageScale() {
        assertEquals(0.8f, pageScaleForTap(10f, 100, 20), 0.0001f)
        assertEquals(0.95f, pageScaleForTap(50f, 100, 20), 0.0001f)
        assertEquals(1f, pageScaleForTap(63.5f, 100, 20), 0.0001f)
        assertEquals(1.1f, pageScaleForTap(90f, 100, 20), 0.0001f)
    }

    @Test
    fun fromConfigMapsAndNormalizesThemeSettings() {
        val state = ThemeSettingsUiState.fromConfig(
            ModuleConfig(
                themeMode = "DARK",
                enableMonet = false,
                pageScale = 1.2f,
                enableNavigationBadge = false,
                enableBlur = false,
                enableFloatingBottomBar = false,
                enableFloatingBottomBarBlur = false,
            ),
        )

        assertEquals(ThemeMode.DARK, state.themeMode)
        assertEquals(false, state.enableMonet)
        assertEquals(1.1f, state.pageScale)
        assertEquals(false, state.enableNavigationBadge)
        assertEquals(false, state.enableBlur)
        assertEquals(false, state.enableFloatingBottomBar)
        assertEquals(false, state.enableFloatingBottomBarBlur)
    }
}
