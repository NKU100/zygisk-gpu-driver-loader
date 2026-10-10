package io.github.nku100.webui.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.nku100.webui.platform.awaitNextFrame
import io.github.nku100.webui.platform.PlatformBackHandler
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nku100.webui.platform.isAndroidPlatform
import io.github.nku100.webui.platform.navigationBarBottomPadding
import io.github.nku100.webui.ui.component.FloatingBottomBar
import io.github.nku100.webui.ui.component.FloatingBottomBarItem
import io.github.nku100.webui.ui.util.defaultBlurEffect
import io.github.nku100.webui.ui.util.rememberContentReady
import io.github.nku100.webui.ui.util.rememberDefaultBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Provides MainPagerState to the entire pager subtree, mirroring KSU's LocalMainPagerState. */
val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> {
    error("LocalMainPagerState not provided")
}

@Composable
fun MainScreen(viewModel: MainViewModel, uiState: MainUiState, onPagerStateReady: (MainPagerState) -> Unit = {}) {
    val scope = rememberCoroutineScope()

    val config = uiState.config
    val targetAppCount = config.targetPackages.toSet().size
    val enableFloatingBottomBar = config.enableFloatingBottomBar
    val enableFloatingBottomBarBlur = config.enableFloatingBottomBarBlur && enableFloatingBottomBar

    val surfaceColor = MiuixTheme.colorScheme.surface
    val blurBackdrop = rememberDefaultBlurBackdrop(config.enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val pagerState = rememberPagerState(pageCount = { BottomTab.entries.size })
    val mainPagerState = rememberMainPagerState(pagerState, scope)

    // Notify App-level BrowserHistorySync about our pager state
    LaunchedEffect(mainPagerState) {
        onPagerStateReady(mainPagerState)
    }

    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    val isBackHandlerEnabled by remember(mainPagerState) {
        derivedStateOf { mainPagerState.selectedPage != 0 }
    }
    PlatformBackHandler(enabled = isBackHandlerEnabled) {
        mainPagerState.animateToPage(0)
    }

    val items = BottomTab.entries.map { tab ->
        NavigationItem(label = stringResource(tab.labelRes), icon = tab.icon)
    }

    val bottomBar = @Composable {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (enableFloatingBottomBar) {
                FloatingBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clickable(interactionSource = null, indication = null, onClick = {})
                        .padding(
                            bottom = 12.dp + navigationBarBottomPadding()
                        ),
                    selectedIndex = mainPagerState.selectedPage,
                    onSelected = mainPagerState::animateToPage,
                    backdrop = backdrop,
                    tabsCount = items.size,
                    isBlurEnabled = enableFloatingBottomBarBlur,
                ) { activateTab ->
                    items.forEachIndexed { index, item ->
                        val badge = targetAppCountBadge(
                            count = targetAppCount.takeIf {
                                config.enableNavigationBadge && index == BottomTab.APPS.ordinal
                            } ?: 0,
                            floating = true,
                        )
                        FloatingBottomBarItem(
                            selected = mainPagerState.selectedPage == index,
                            onClick = { activateTab(index) },
                            modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                        ) {
                            val icon: @Composable () -> Unit = {
                                Icon(imageVector = item.icon, contentDescription = item.label)
                            }
                            if (badge != null) {
                                BadgedBox(badge = { badge() }) { icon() }
                            } else {
                                icon()
                            }
                            Text(
                                text = item.label,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }
                }
            } else {
                val navBarPadding = if (!isAndroidPlatform) navigationBarBottomPadding() else 0.dp
                NavigationBar(
                    modifier = (blurBackdrop?.let { Modifier.defaultBlurEffect(it) } ?: Modifier)
                        .padding(bottom = navBarPadding),
                    color = if (config.enableBlur) Color.Transparent else MiuixTheme.colorScheme.surface,
                    content = {
                        items.forEachIndexed { index, item ->
                            val badge = targetAppCountBadge(
                                count = targetAppCount.takeIf {
                                    config.enableNavigationBadge && index == BottomTab.APPS.ordinal
                                } ?: 0,
                                floating = false,
                            )
                            NavigationBarItem(
                                modifier = Modifier.weight(1f),
                                icon = item.icon,
                                label = item.label,
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                                badge = badge,
                            )
                        }
                    }
                )
            }
        }
    }

    CompositionLocalProvider(LocalMainPagerState provides mainPagerState) {
        // Progressive warm-up: increment beyondViewportPageCount by 1 per frame
        // to avoid composing all 4 pages in a single frame during cold start.
        // contentReady is the legacy flag (true after nav transition completes).
        val contentReady = rememberContentReady()
        val warmUpCount = remember { mutableIntStateOf(0) }
        LaunchedEffect(contentReady) {
            if (contentReady && warmUpCount.intValue < 3) {
                while (warmUpCount.intValue < 3) {
                    awaitNextFrame()
                    warmUpCount.intValue++
                }
            }
        }
        val beyondViewportPages = if (!contentReady) 0
        else minOf(warmUpCount.intValue, 3)

        Scaffold(bottomBar = bottomBar) { innerPadding ->
            HorizontalPager(
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                    .then(if (enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                state = pagerState,
                beyondViewportPageCount = beyondViewportPages,
                userScrollEnabled = true,
            ) { page ->
                val isCurrentPage = page == pagerState.settledPage
                if (isCurrentPage || beyondViewportPages > 0) {
                    PlaceholderPage(
                        tab = BottomTab.entries[page],
                        uiState = uiState,
                        bottomPadding = innerPadding.calculateBottomPadding(),
                        onNavigateToTab = { mainPagerState.animateToPage(it) },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun targetAppCountBadge(count: Int, floating: Boolean): (@Composable () -> Unit)? {
    if (count <= 0) return null
    val colors = MiuixTheme.colorScheme
    val background = if (floating) colors.primaryContainer else colors.primary
    val content = if (floating) colors.onPrimaryContainer else colors.onPrimary
    val label = if (count > 99) "99+" else count.toString()
    return {
        Badge(containerColor = background, contentColor = content) {
            Text(text = label, fontSize = 9.sp, lineHeight = 11.sp)
        }
    }
}
