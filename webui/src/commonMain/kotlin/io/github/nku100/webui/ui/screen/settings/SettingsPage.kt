package io.github.nku100.webui.ui.screen.settings
import org.jetbrains.compose.resources.stringResource
import zygisk_module_webui_template.webui.generated.resources.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Update
import io.github.nku100.webui.platform.isAndroidPlatform
import io.github.nku100.webui.ui.theme.ThemeMode
import io.github.nku100.webui.ui.util.rememberDefaultBlurBackdrop
import io.github.nku100.webui.ui.util.topBarDefaultWindowInsetsPadding
import io.github.nku100.webui.ui.util.topBarModifier
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

@Composable
fun SettingsPage(
    uiState: SettingsUiState,
    actions: SettingsActions,
    bottomPadding: Dp,
    enableBlur: Boolean,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberDefaultBlurBackdrop(enableBlur)

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.topBarModifier(blurBackdrop),
                color = if (enableBlur) Color.Transparent else colorScheme.surface,
                title = stringResource(Res.string.tab_settings),
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = topBarDefaultWindowInsetsPadding,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .then(blurBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            // Module Enabled
            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.module_enabled),
                        summary = stringResource(Res.string.module_enabled_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.PowerSettingsNew,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(Res.string.module_enabled),
                                tint = colorScheme.onBackground
                            )
                        },
                        checked = uiState.enabled,
                        onCheckedChange = actions.onEnabledChange
                    )
                }
            }

            // Theme Mode
            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    val themeModeItems = ThemeMode.entries.map { mode ->
                        when (mode) {
                            ThemeMode.FOLLOW_SYSTEM -> stringResource(Res.string.theme_follow_system)
                            ThemeMode.LIGHT -> stringResource(Res.string.theme_light)
                            ThemeMode.DARK -> stringResource(Res.string.theme_dark)
                        }
                    }
                    OverlayDropdownPreference(
                        title = stringResource(Res.string.theme_mode),
                        summary = stringResource(Res.string.theme_mode_summary),
                        items = themeModeItems,
                        startAction = {
                            Icon(
                                Icons.Rounded.Palette,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(Res.string.theme_mode),
                                tint = colorScheme.onBackground
                            )
                        },
                        selectedIndex = ThemeMode.entries.indexOf(uiState.themeMode),
                        onSelectedIndexChange = { index ->
                            actions.onThemeModeChange(ThemeMode.entries[index])
                        }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.dynamic_colors),
                        summary = stringResource(Res.string.dynamic_colors_summary),
                        checked = uiState.enableMonet,
                        onCheckedChange = actions.onEnableMonetChange,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    var sliderValue by remember(uiState.pageScale) {
                        mutableFloatStateOf(uiState.pageScale.coerceIn(0.8f, 1.1f))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.page_scale),
                                    color = colorScheme.onBackground,
                                )
                                Text(
                                    text = stringResource(Res.string.page_scale_summary),
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                text = "${(sliderValue * 100).roundToInt()}%",
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { actions.onPageScaleChange(sliderValue) },
                            valueRange = 0.8f..1.1f,
                            showKeyPoints = true,
                            keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                            magnetThreshold = 0.01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        )
                    }
                }
            }

            // Update Channel (only visible when module.prop updateJson matches known pattern)
            item {
                AnimatedVisibility(visible = uiState.updateChannelVisible) {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        val channelItems = UpdateChannel.entries.map { channel ->
                            when (channel) {
                                UpdateChannel.STABLE -> stringResource(Res.string.update_channel_stable)
                                UpdateChannel.BETA -> stringResource(Res.string.update_channel_beta)
                            }
                        }
                        OverlayDropdownPreference(
                            title = stringResource(Res.string.update_channel),
                            summary = stringResource(Res.string.update_channel_summary),
                            items = channelItems,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Update,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(Res.string.update_channel),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = UpdateChannel.entries.indexOf(uiState.updateChannel),
                            onSelectedIndexChange = { index ->
                                actions.onUpdateChannelChange(UpdateChannel.entries[index])
                            }
                        )
                    }
                }
            }

            // UI Effects
            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.blur_effects),
                        summary = stringResource(Res.string.blur_effects_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.BlurOn,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(Res.string.blur_effects),
                                tint = colorScheme.onBackground
                            )
                        },
                        checked = uiState.enableBlur,
                        onCheckedChange = actions.onEnableBlurChange
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.floating_bottom_bar),
                        summary = stringResource(Res.string.floating_bottom_bar_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.CallToAction,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(Res.string.floating_bottom_bar),
                                tint = colorScheme.onBackground
                            )
                        },
                        checked = uiState.enableFloatingBottomBar,
                        onCheckedChange = actions.onEnableFloatingBottomBarChange
                    )
                    AnimatedVisibility(visible = uiState.enableFloatingBottomBar) {
                        SwitchPreference(
                            title = stringResource(Res.string.bottom_bar_glass_effect),
                            summary = stringResource(Res.string.bottom_bar_glass_effect_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.WaterDrop,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(Res.string.bottom_bar_glass_effect),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.enableFloatingBottomBarBlur,
                            onCheckedChange = actions.onEnableFloatingBottomBarBlurChange
                        )
                    }
                    SwitchPreference(
                        title = stringResource(Res.string.navigation_badge),
                        summary = stringResource(Res.string.navigation_badge_summary),
                        checked = uiState.enableNavigationBadge,
                        onCheckedChange = actions.onNavigationBadgeChange,
                    )
                }
            }

            // About
            item {
                Card(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = stringResource(Res.string.about),
                        summary = stringResource(Res.string.about_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.ContactPage,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(Res.string.about),
                                tint = colorScheme.onBackground
                            )
                        },
                        onClick = actions.onOpenAbout,
                    )
                }
                Spacer(Modifier.height(bottomPadding))
            }
        }
    }
}
