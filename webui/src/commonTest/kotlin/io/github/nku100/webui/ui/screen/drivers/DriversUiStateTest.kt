package io.github.nku100.webui.ui.screen.drivers

import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.data.PackageSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriversUiStateTest {
    private val installed = DriverInfo("driver-a", "Driver A", "libvulkan.so", "arm64-v8a")

    @Test
    fun bindingChangesOnlyTheSelectedPackageDriver() {
        val original = ModuleConfig(
            targetPackages = listOf("app.one"),
            packageSettings = mapOf(
                "app.one" to PackageSettings(logLevel = "DEBUG", logTag = "trace", dumpStackTrace = true, note = "keep"),
                "app.two" to PackageSettings(driverId = "other", logLevel = "WARN"),
            ),
        )

        val changed = original.withPackageDriver("app.one", "driver-a", listOf(installed))

        assertEquals("driver-a", changed?.packageSettings?.get("app.one")?.driverId)
        assertEquals("DEBUG", changed?.packageSettings?.get("app.one")?.logLevel)
        assertEquals("trace", changed?.packageSettings?.get("app.one")?.logTag)
        assertEquals(true, changed?.packageSettings?.get("app.one")?.dumpStackTrace)
        assertEquals("keep", changed?.packageSettings?.get("app.one")?.note)
        assertEquals(original.packageSettings["app.two"], changed?.packageSettings?.get("app.two"))
        assertEquals(original.targetPackages, changed?.targetPackages)
    }

    @Test
    fun systemDriverClearsBindingAndUnknownDriverIsRejected() {
        val config = ModuleConfig(packageSettings = mapOf("app.one" to PackageSettings(driverId = "driver-a", note = "keep")))

        assertEquals("", config.withPackageDriver("app.one", "", listOf(installed))?.packageSettings?.get("app.one")?.driverId)
        assertEquals("keep", config.withPackageDriver("app.one", "", listOf(installed))?.packageSettings?.get("app.one")?.note)
        assertNull(config.withPackageDriver("app.one", "missing", listOf(installed)))
    }

    @Test
    fun boundDriverCannotBeDeletedFromManagementScreen() {
        val config = ModuleConfig(packageSettings = mapOf("app.one" to PackageSettings(driverId = "driver-a")))

        assertTrue(DriversUiState(drivers = listOf(installed)).isBound("driver-a", config))
        assertFalse(DriversUiState(drivers = listOf(installed)).isBound("other", config))
    }

    @Test
    fun failedImportKeepsTheInstalledList() {
        val state = DriversUiState(drivers = listOf(installed), isBusy = true)

        val afterFailure = state.afterImportFailure(DriverArchiveError.INVALID_ZIP)

        assertEquals(listOf(installed), afterFailure.drivers)
        assertEquals(DriverArchiveError.INVALID_ZIP, afterFailure.importError)
    }

    @Test
    fun failedInitialListReadDoesNotClaimThereAreNoDriversAndCanRecover() {
        val failed = DriversUiState().afterListFailure()

        assertEquals(DriverListStatus.UNAVAILABLE, failed.listStatus)
        assertFalse(failed.showEmptyState)
        assertTrue(failed.canRetryList)

        val recovered = failed.withVerifiedList(emptyList())
        assertEquals(DriverListStatus.FRESH, recovered.listStatus)
        assertTrue(recovered.showEmptyState)
    }

    @Test
    fun successfulImportRemainsVisibleWhenFollowUpListReadFails() {
        val imported = DriverInfo("driver-b", "Driver B", "libb.so", "arm64-v8a", 1234, "abcdef")
        val state = DriversUiState(drivers = listOf(installed), listStatus = DriverListStatus.FRESH)

        val afterRefreshFailure = state.afterSuccessfulImport(imported).afterListFailure()

        assertEquals(listOf(installed, imported), afterRefreshFailure.drivers)
        assertEquals(DriverListStatus.STALE, afterRefreshFailure.listStatus)
        assertNull(afterRefreshFailure.importError)
        assertTrue(afterRefreshFailure.canRetryList)
    }

    @Test
    fun successfulDeletionRemainsVisibleWhenFollowUpListReadFails() {
        val state = DriversUiState(drivers = listOf(installed), listStatus = DriverListStatus.FRESH)

        val afterRefreshFailure = state.afterSuccessfulDelete("driver-a").afterListFailure()

        assertTrue(afterRefreshFailure.drivers.isEmpty())
        assertFalse(afterRefreshFailure.showEmptyState)
        assertEquals(DriverListStatus.STALE, afterRefreshFailure.listStatus)
        assertNull(afterRefreshFailure.deleteError)
        assertTrue(afterRefreshFailure.canRetryList)
    }
}
