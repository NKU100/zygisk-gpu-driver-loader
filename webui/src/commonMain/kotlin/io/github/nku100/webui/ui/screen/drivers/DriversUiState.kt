package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.runtime.Immutable
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.data.PackageSettings

@Immutable
data class DriversUiState(
    val drivers: List<DriverInfo> = emptyList(),
    val canImport: Boolean = true,
    val isBusy: Boolean = false,
    val loadFailed: Boolean = false,
    val importError: DriverArchiveError? = null,
    val deleteError: DriverDeleteResult? = null,
) {
    fun isBound(driverId: String, config: ModuleConfig): Boolean =
        config.packageSettings.values.any { it.driverId == driverId }

    fun afterImportFailure(error: DriverArchiveError): DriversUiState = copy(importError = error)
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
