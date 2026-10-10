package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.ui.theme.AppTheme
import io.github.nku100.webui.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class DriverListItemTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun touchingDriverRowSelectsThatDriverOnce() = runComposeUiTest {
        val driver = DriverInfo(
            driverId = "test-driver-id",
            name = "Regression GPU Driver",
            libraryName = "libvulkan_test.so",
            abi = "arm64-v8a",
        )
        var selectedDriverId: String? = null
        var selectionCount = 0
        val onSelectDriver: (String) -> Unit = { driverId ->
            selectedDriverId = driverId
            selectionCount++
        }

        setContent {
            AppTheme(ThemeMode.LIGHT) {
                DriverListItem(driver = driver, shape = RoundedCornerShape(18.dp), onClick = onSelectDriver)
            }
        }

        onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        onNodeWithText(driver.name, useUnmergedTree = true)
            .performTouchInput { click() }

        assertEquals(driver.driverId, selectedDriverId)
        assertEquals(1, selectionCount)
    }
}
