package io.github.nku100.webui.ui.screen.settings

import androidx.compose.runtime.Immutable
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.ui.theme.ThemeMode
import io.github.nku100.webui.ui.theme.normalizePageScale

@Immutable
data class SettingsUiState(
    val enabled: Boolean = true,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val updateChannelVisible: Boolean = false,
) {
    companion object {
        fun fromConfig(
            config: ModuleConfig,
            updateChannel: UpdateChannel = UpdateChannel.STABLE,
            updateChannelVisible: Boolean = false,
        ): SettingsUiState {
            return SettingsUiState(
                enabled = config.enabled,
                updateChannel = updateChannel,
                updateChannelVisible = updateChannelVisible,
            )
        }
    }
}

@Immutable
data class ThemeSettingsUiState(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val enableMonet: Boolean = true,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1f,
) {
    companion object {
        fun fromConfig(config: ModuleConfig) = ThemeSettingsUiState(
            themeMode = ThemeMode.entries.find { it.name == config.themeMode }
                ?: ThemeMode.FOLLOW_SYSTEM,
            enableMonet = config.enableMonet,
            enableBlur = config.enableBlur,
            enableFloatingBottomBar = config.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = config.enableFloatingBottomBarBlur,
            enableNavigationBadge = config.enableNavigationBadge,
            pageScale = normalizePageScale(config.pageScale),
        )
    }
}

data class SettingsActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onUpdateChannelChange: (UpdateChannel) -> Unit,
    val onOpenThemeSettings: () -> Unit,
    val onOpenAbout: () -> Unit = {},
    val onOpenDrivers: () -> Unit = {},
)

data class ThemeSettingsActions(
    val onBack: () -> Unit,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onEnableMonetChange: (Boolean) -> Unit,
    val onEnableBlurChange: (Boolean) -> Unit,
    val onEnableFloatingBottomBarChange: (Boolean) -> Unit,
    val onEnableFloatingBottomBarBlurChange: (Boolean) -> Unit,
    val onNavigationBadgeChange: (Boolean) -> Unit,
    val onPageScaleChange: (Float) -> Unit,
)
