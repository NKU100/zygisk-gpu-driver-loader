package io.github.nku100.webui.ui.screen.settings

import androidx.compose.runtime.Immutable
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.ui.theme.ThemeMode
import io.github.nku100.webui.ui.theme.normalizePageScale

@Immutable
data class SettingsUiState(
    val enabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val updateChannelVisible: Boolean = false,
    val enableMonet: Boolean = true,
    val pageScale: Float = 1f,
    val enableNavigationBadge: Boolean = true,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
) {
    companion object {
        fun fromConfig(
            config: ModuleConfig,
            updateChannel: UpdateChannel = UpdateChannel.STABLE,
            updateChannelVisible: Boolean = false,
        ): SettingsUiState {
            return SettingsUiState(
                enabled = config.enabled,
                themeMode = ThemeMode.entries.find { it.name == config.themeMode }
                    ?: ThemeMode.FOLLOW_SYSTEM,
                updateChannel = updateChannel,
                updateChannelVisible = updateChannelVisible,
                enableMonet = config.enableMonet,
                pageScale = normalizePageScale(config.pageScale),
                enableNavigationBadge = config.enableNavigationBadge,
                enableBlur = config.enableBlur,
                enableFloatingBottomBar = config.enableFloatingBottomBar,
                enableFloatingBottomBarBlur = config.enableFloatingBottomBarBlur,
            )
        }
    }
}

data class SettingsActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onUpdateChannelChange: (UpdateChannel) -> Unit,
    val onEnableMonetChange: (Boolean) -> Unit,
    val onPageScaleChange: (Float) -> Unit,
    val onNavigationBadgeChange: (Boolean) -> Unit,
    val onEnableBlurChange: (Boolean) -> Unit,
    val onEnableFloatingBottomBarChange: (Boolean) -> Unit,
    val onEnableFloatingBottomBarBlurChange: (Boolean) -> Unit,
    val onOpenAbout: () -> Unit = {},
)
