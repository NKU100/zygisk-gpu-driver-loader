package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.data.PackageSettings
import io.github.nku100.webui.platform.PackageInfo
import io.github.nku100.webui.ui.theme.AppTheme
import io.github.nku100.webui.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriversPageTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun boundDriverCanBeDeletedThroughConfirmationInsteadOfBeingBlocked() = runComposeUiTest {
        val driver = DriverInfo(
            driverId = "test-driver-id",
            name = "Assigned GPU Driver",
            libraryName = "libvulkan_test.so",
            abi = "arm64-v8a",
            archiveSha256 = "deadbeefcaf0faded12345678901234567890123456789012345678901234567",
        )
        val config = ModuleConfig(
            targetPackages = listOf("example.app"),
            packageSettings = mapOf(
                "example.app" to PackageSettings(driverId = driver.driverId),
                "example.background" to PackageSettings(driverId = driver.driverId),
            ),
        )
        var resetBindings = false

        setContent {
            AppTheme(ThemeMode.LIGHT) {
                DriversPage(
                    state = DriversUiState(drivers = listOf(driver), listStatus = DriverListStatus.FRESH),
                    config = config,
                    packages = listOf(
                        PackageInfo("example.app", "Example App"),
                        PackageInfo("example.background", "Background App"),
                    ),
                    onBack = {},
                    onOpenZipPicker = {},
                    onBrowseZipDirectory = {},
                    onSelectZip = {},
                    onRetryZipDirectory = {},
                    onCancelZipPicker = {},
                    onImportSelectedZip = {},
                    onDelete = { _, reset -> resetBindings = reset },
                    onRetryList = {},
                    bottomPadding = 0.dp,
                    enableBlur = false,
                )
            }
        }

        onNodeWithText(driver.name, useUnmergedTree = true).performTouchInput { click() }
        onNodeWithTag("driver-delete-entry").assertIsEnabled()
        onNodeWithTag("driver-delete-entry").performTouchInput { click() }
        onNodeWithText("Example App").assertExists()
        onNodeWithText("example.app").assertExists()
        onNodeWithText("Background App").assertExists()
        onNodeWithTag("driver-binding-status-example.background").assertExists()
        onNodeWithTag("driver-delete-confirm").assertIsEnabled()
        onNodeWithTag("driver-delete-confirm").performTouchInput { click() }
        assertTrue(resetBindings)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun deleteConfirmationPlacesDestructiveActionAboveNeutralCancel() = runComposeUiTest {
        var deleteCalls = 0
        val driver = DriverInfo(
            driverId = "test-driver-id",
            name = "Regression GPU Driver",
            libraryName = "libvulkan_test.so",
            abi = "arm64-v8a",
            archiveSha256 = "deadbeefcaf0faded12345678901234567890123456789012345678901234567",
        )

        setContent {
            AppTheme(ThemeMode.LIGHT) {
                DriversPage(
                    state = DriversUiState(drivers = listOf(driver), listStatus = DriverListStatus.FRESH),
                    config = ModuleConfig(),
                    onBack = {},
                    onOpenZipPicker = {},
                    onBrowseZipDirectory = {},
                    onSelectZip = {},
                    onRetryZipDirectory = {},
                    onCancelZipPicker = {},
                    onImportSelectedZip = {},
                    onDelete = { _, _ -> deleteCalls++ },
                    onRetryList = {},
                    bottomPadding = 0.dp,
                    enableBlur = false,
                )
            }
        }

        onNodeWithText(driver.name, useUnmergedTree = true).performTouchInput { click() }
        onNodeWithText("deadbeefcaf0", substring = true).assertExists()
        onNodeWithTag("driver-delete-entry").assertWidthIsAtLeast(240.dp)
        onNodeWithTag("driver-delete-entry").assertHeightIsEqualTo(48.dp)
        onNodeWithTag("driver-delete-entry").performTouchInput { click() }
        onNodeWithTag("driver-delete-confirm").assertWidthIsAtLeast(240.dp)
        onNodeWithTag("driver-delete-cancel").assertWidthIsAtLeast(240.dp)
        onNodeWithTag("driver-delete-confirm").assertHeightIsEqualTo(48.dp)
        onNodeWithTag("driver-delete-cancel").assertHeightIsEqualTo(48.dp)
        val deleteTop = onNodeWithTag("driver-delete-confirm").fetchSemanticsNode().boundsInRoot.top
        val cancelTop = onNodeWithTag("driver-delete-cancel").fetchSemanticsNode().boundsInRoot.top
        assertTrue(deleteTop < cancelTop, "The destructive action should appear above Cancel")
        onNodeWithTag("driver-delete-cancel").performTouchInput { click() }
        onNodeWithText(driver.name, useUnmergedTree = true).assertExists()
        assertEquals(0, deleteCalls)
    }
}
