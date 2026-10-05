package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.nku100.webui.data.DriverDirectory
import io.github.nku100.webui.data.DriverPathEntry
import io.github.nku100.webui.data.DriverPathError
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.ui.theme.AppTheme
import io.github.nku100.webui.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class DriverZipPickerDialogTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun loadingKeepsPickerViewportAlignedWithDirectoryListing() = runComposeUiTest {
        var picker by mutableStateOf(
            DriverZipPickerState(
                isOpen = true,
                path = DriverZipPickerState.DOWNLOADS_PATH,
                isLoading = true,
            ),
        )

        setContent {
            AppTheme(ThemeMode.LIGHT) {
                DriversPage(
                    state = DriversUiState(zipPicker = picker),
                    config = ModuleConfig(),
                    onBack = {},
                    onOpenZipPicker = {},
                    onBrowseZipDirectory = {},
                    onSelectZip = {},
                    onRetryZipDirectory = {},
                    onCancelZipPicker = {},
                    onImportSelectedZip = {},
                    onDelete = { _, _ -> },
                    onRetryList = {},
                    bottomPadding = 0.dp,
                    enableBlur = false,
                )
            }
        }

        waitForIdle()
        assertEquals(0, onAllNodesWithText("Loading directory…").fetchSemanticsNodes().size)
        val loadingGap = pickerHeaderToCancelGap()
        picker = picker.loaded(
            DriverDirectory(
                path = picker.path,
                entries = listOf(DriverPathEntry("drivers", isDirectory = true)),
            ),
        )
        waitForIdle()
        val listingGap = pickerHeaderToCancelGap()

        picker = picker.copy(entries = emptyList())
        waitForIdle()
        val emptyDirectoryGap = pickerHeaderToCancelGap()

        picker = picker.failed(DriverPathError.ACCESS_DENIED)
        waitForIdle()
        val errorGap = pickerHeaderToCancelGap()

        assertEquals(loadingGap, listingGap)
        assertEquals(loadingGap, emptyDirectoryGap)
        assertEquals(loadingGap, errorGap)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun folderNavigationStartsDirectoryReadImmediately() = runComposeUiTest {
        var picker by mutableStateOf(
            DriverZipPickerState(
                isOpen = true,
                path = DriverZipPickerState.DOWNLOADS_PATH,
                entries = listOf(DriverPathEntry("Games", isDirectory = true)),
            ),
        )
        var requestedPath: String? = null

        setContent {
            AppTheme(ThemeMode.LIGHT) {
                DriversPage(
                    state = DriversUiState(zipPicker = picker),
                    config = ModuleConfig(),
                    onBack = {},
                    onOpenZipPicker = {},
                    onBrowseZipDirectory = { path ->
                        requestedPath = path
                        picker = picker.loading(path)
                    },
                    onSelectZip = {},
                    onRetryZipDirectory = {},
                    onCancelZipPicker = {},
                    onImportSelectedZip = {},
                    onDelete = { _, _ -> },
                    onRetryList = {},
                    bottomPadding = 0.dp,
                    enableBlur = false,
                )
            }
        }

        waitForIdle()
        mainClock.autoAdvance = false
        onNodeWithText("Games", useUnmergedTree = true).performTouchInput { click() }
        waitForIdle()
        assertEquals("${DriverZipPickerState.DOWNLOADS_PATH}/Games", requestedPath)
        assertEquals(1, onAllNodesWithText("Games", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.pickerHeaderToCancelGap(): Float {
        val breadcrumbBottom = onNodeWithText("Download", substring = true).fetchSemanticsNode().boundsInRoot.bottom
        val footerButtons = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        val cancelTop = footerButtons[footerButtons.lastIndex - 1].boundsInRoot.top
        return cancelTop - breadcrumbBottom
    }
}
