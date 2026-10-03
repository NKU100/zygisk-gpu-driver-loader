package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.runtime.Immutable
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverDirectory
import io.github.nku100.webui.data.DriverPathEntry
import io.github.nku100.webui.data.DriverPathError
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.data.PackageSettings

enum class DriverListStatus { LOADING, FRESH, UNAVAILABLE, STALE }

@Immutable
data class DriverZipPickerState(
    val isOpen: Boolean = false,
    val path: String = DOWNLOADS_PATH,
    val entries: List<DriverPathEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: DriverPathError? = null,
    val selectedFileName: String? = null,
) {
    val isAtStorageRoot: Boolean get() = path == STORAGE_ROOT
    val selectedZipPath: String? get() = selectedFileName?.let { "$path/$it" }

    fun open(): DriverZipPickerState = copy(
        isOpen = true,
        path = DOWNLOADS_PATH,
        entries = emptyList(),
        isLoading = true,
        error = null,
        selectedFileName = null,
    )

    fun loading(nextPath: String): DriverZipPickerState = if (isSafeDirectoryPath(nextPath)) copy(
        path = nextPath,
        entries = emptyList(),
        isLoading = true,
        error = null,
        selectedFileName = null,
    ) else failed(DriverPathError.INVALID_PATH)

    fun loaded(directory: DriverDirectory): DriverZipPickerState = if (directory.path == path) copy(
        entries = directory.entries,
        isLoading = false,
        error = null,
    ) else failed(DriverPathError.STORAGE_ERROR)

    fun failed(pathError: DriverPathError): DriverZipPickerState = copy(
        entries = emptyList(),
        isLoading = false,
        error = pathError,
        selectedFileName = null,
    )

    fun select(entry: DriverPathEntry): DriverZipPickerState = if (
        !entry.isDirectory && entries.contains(entry) && entry.name.endsWith(".zip", ignoreCase = true)
    ) copy(selectedFileName = entry.name) else this

    fun childPath(entry: DriverPathEntry): String? = if (
        entry.isDirectory && entries.contains(entry) && isSafePathSegment(entry.name)
    ) "$path/${entry.name}" else null

    fun parentPath(): String? {
        if (!isSafeDirectoryPath(path) || isAtStorageRoot) return null
        return path.substringBeforeLast('/').takeIf { it.isNotEmpty() }
    }

    fun close(): DriverZipPickerState = DriverZipPickerState()

    private fun isSafeDirectoryPath(candidate: String): Boolean =
        (candidate == STORAGE_ROOT || candidate.startsWith("$STORAGE_ROOT/")) &&
            '\\' !in candidate && '\u0000' !in candidate &&
            candidate.removePrefix("$STORAGE_ROOT").split('/').all { it.isEmpty() || it != "." && it != ".." }

    private fun isSafePathSegment(segment: String): Boolean =
        segment.isNotEmpty() && segment != "." && segment != ".." && '/' !in segment &&
            '\\' !in segment && '\u0000' !in segment

    companion object {
        const val STORAGE_ROOT = "/storage/emulated/0"
        const val DOWNLOADS_PATH = "$STORAGE_ROOT/Download"
    }
}

@Immutable
data class DriversUiState(
    val drivers: List<DriverInfo> = emptyList(),
    val canImport: Boolean = true,
    val isBusy: Boolean = false,
    val listStatus: DriverListStatus = DriverListStatus.LOADING,
    val importError: DriverArchiveError? = null,
    val importedDriver: DriverInfo? = null,
    val deleteError: DriverDeleteResult? = null,
    val zipPicker: DriverZipPickerState = DriverZipPickerState(),
) {
    val showEmptyState: Boolean get() = listStatus == DriverListStatus.FRESH && drivers.isEmpty()
    val canRetryList: Boolean get() = listStatus == DriverListStatus.UNAVAILABLE || listStatus == DriverListStatus.STALE

    fun isBound(driverId: String, config: ModuleConfig): Boolean =
        config.packageSettings.values.any { it.driverId == driverId }

    fun afterImportFailure(error: DriverArchiveError): DriversUiState = copy(importError = error, importedDriver = null)

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
        importedDriver = driver,
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
