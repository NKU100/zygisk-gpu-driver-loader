package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.runtime.Immutable
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.data.PackageSettings

enum class DriverListStatus { LOADING, FRESH, UNAVAILABLE, STALE }

@Immutable
data class DriversUiState(
    val drivers: List<DriverInfo> = emptyList(),
    val canImport: Boolean = true,
    val isBusy: Boolean = false,
    val listStatus: DriverListStatus = DriverListStatus.LOADING,
    val importError: DriverArchiveError? = null,
    val deleteError: DriverDeleteResult? = null,
) {
    val showEmptyState: Boolean get() = listStatus == DriverListStatus.FRESH && drivers.isEmpty()
    val canRetryList: Boolean get() = listStatus == DriverListStatus.UNAVAILABLE || listStatus == DriverListStatus.STALE

    fun isBound(driverId: String, config: ModuleConfig): Boolean =
        config.packageSettings.values.any { it.driverId == driverId }

    fun afterImportFailure(error: DriverArchiveError): DriversUiState = copy(importError = error)

    fun withVerifiedList(installed: List<DriverInfo>): DriversUiState =
        copy(drivers = installed, listStatus = DriverListStatus.FRESH)

    fun afterListFailure(): DriversUiState = copy(
        listStatus = if (listStatus == DriverListStatus.LOADING || listStatus == DriverListStatus.UNAVAILABLE)
            DriverListStatus.UNAVAILABLE else DriverListStatus.STALE,
    )

    fun afterSuccessfulImport(driver: DriverInfo): DriversUiState = copy(
        drivers = drivers.filterNot { it.driverId == driver.driverId } + driver,
        listStatus = DriverListStatus.STALE,
        importError = null,
    )

    fun afterSuccessfulDelete(driverId: String): DriversUiState = copy(
        drivers = drivers.filterNot { it.driverId == driverId },
        listStatus = DriverListStatus.STALE,
        deleteError = null,
    )
}

fun ModuleConfig.withPackageDriver(
    packageName: String,
    driverId: String,
    installedDrivers: List<DriverInfo>,
): ModuleConfig? {
    if (driverId.isNotEmpty() && installedDrivers.none { it.driverId == driverId }) return null
    val settings = packageSettings[packageName] ?: PackageSettings()
    return copy(packageSettings = packageSettings + (packageName to settings.copy(driverId = driverId)))
}
