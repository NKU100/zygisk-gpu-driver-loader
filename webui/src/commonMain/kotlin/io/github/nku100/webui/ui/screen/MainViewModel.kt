package io.github.nku100.webui.ui.screen

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nku100.webui.ModuleInfo
import io.github.nku100.webui.data.ConfigRepository
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverImportResult
import io.github.nku100.webui.data.DriverPathException
import io.github.nku100.webui.data.DriverPathError
import io.github.nku100.webui.data.DriverPathEntry
import io.github.nku100.webui.data.DriverRepository
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.data.PackageSettings
import io.github.nku100.webui.platform.PackageInfo
import io.github.nku100.webui.platform.PlatformBridge
import io.github.nku100.webui.platform.RootAccess
import io.github.nku100.webui.platform.RootEnvironment
import io.github.nku100.webui.platform.awaitNextFrame
import io.github.nku100.webui.platform.hasPlatformApi
import io.github.nku100.webui.ui.component.SearchStatus
import io.github.nku100.webui.ui.screen.drivers.DriversUiState
import io.github.nku100.webui.ui.screen.drivers.DriverListStatus
import io.github.nku100.webui.ui.screen.drivers.DriverZipPickerState
import io.github.nku100.webui.ui.screen.drivers.withPackageDriver
import io.github.nku100.webui.ui.theme.ThemeMode
import io.github.nku100.webui.ui.screen.settings.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class MainUiState(
    val rootEnvironment: RootEnvironment? = null,
    val packageListFailed: Boolean = false,
    val config: ModuleConfig = ModuleConfig(),
    val drivers: DriversUiState = DriversUiState(),
    val packages: List<PackageInfo> = emptyList(),
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val showSystemApps: Boolean = false,
    val appsSearchStatus: SearchStatus = SearchStatus(label = "Search apps..."),
    val searchResults: List<PackageInfo> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val updateChannelVisible: Boolean = false,
    // Dynamic metadata read from module.prop at runtime
    val moduleName: String = "",
    val moduleVersion: String = "",
    val moduleAuthor: String = "",
)

internal fun MainUiState.beginFetch(): MainUiState =
    copy(isLoading = true)

@OptIn(FlowPreview::class)
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        // Launch search query collector with debounce, mirroring KSU's launchSearchQueryCollector
        viewModelScope.launch {
            searchQuery.debounce(300.milliseconds).collect { applySearchText(it) }
        }

        // Auto-load data on init
        viewModelScope.launch {
            if (hasPlatformApi()) {
                fetchData()
            } else {
                loadMockData()
            }
        }
    }

    private suspend fun fetchData() {
        _uiState.update { it.beginFetch() }
        try {
            val environment = RootAccess.environment()
            _uiState.update { it.copy(rootEnvironment = environment) }
            check(environment.available) { "Root access unavailable" }
            val config = ConfigRepository.load()
            val drivers = try {
                DriversUiState().withVerifiedList(DriverRepository.listDrivers())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DriversUiState().afterListFailure()
            }
            _uiState.update { it.copy(drivers = drivers) }
            val rawPackages = PlatformBridge.listPackages()
            val targets = config.targetPackages.toSet()
            val packages = withContext(Dispatchers.Default) { sortPackages(rawPackages, targets) }
            val prop = readModuleProp()
            val channel = prop["updateJson"]?.let { url ->
                when {
                    url.contains(STABLE_PATH) -> UpdateChannel.STABLE
                    url.contains(BETA_PATH) -> UpdateChannel.BETA
                    else -> null
                }
            }
            _uiState.update {
                it.copy(
                    config = config,
                    drivers = drivers,
                    packages = packages,
                    packageListFailed = false,
                    isLoading = false,
                    hasLoaded = true,
                    themeMode = resolveThemeMode(config),
                    updateChannel = channel ?: UpdateChannel.STABLE,
                    updateChannelVisible = channel != null,
                    moduleName = prop["name"].orEmpty(),
                    moduleVersion = prop["version"].orEmpty(),
                    moduleAuthor = prop["author"].orEmpty(),
                )
            }
            // Re-apply active search after data loads so results are not stale
            val currentSearch = searchQuery.value
            if (currentSearch.isNotEmpty()) {
                applySearchText(currentSearch)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasLoaded = true,
                    rootEnvironment = it.rootEnvironment ?: RootEnvironment(),
                    packages = emptyList(),
                    searchResults = emptyList(),
                    packageListFailed = true,
                    drivers = if (it.drivers.listStatus == DriverListStatus.LOADING)
                        it.drivers.afterListFailure() else it.drivers,
                )
            }
        }
    }

    fun refresh(): Job = viewModelScope.launch {
        _uiState.update { it.copy(isRefreshing = true) }
        awaitNextFrame()
        try { fetchData() } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun loadMockData() {
        val targets = setOf("com.example.app", "com.android.chrome", "org.telegram.messenger")
        val rawPackages = listOf(
            PackageInfo(packageName = "com.android.chrome", label = "Chrome"),
            PackageInfo(packageName = "org.telegram.messenger", label = "Telegram"),
            PackageInfo(packageName = "com.example.app", label = "Example App"),
            PackageInfo(packageName = "com.whatsapp", label = "WhatsApp"),
            PackageInfo(packageName = "com.spotify.music", label = "Spotify"),
            PackageInfo(packageName = "com.instagram.android", label = "Instagram"),
            PackageInfo(packageName = "com.twitter.android", label = "X (Twitter)"),
            PackageInfo(packageName = "com.android.settings", label = "Settings", isSystemApp = true),
            PackageInfo(packageName = "com.android.systemui", label = "System UI", isSystemApp = true),
        )
        _uiState.update {
            it.copy(
                config = ModuleConfig(
                    targetPackages = targets.toList(),
                    enabled = true,
                    enableFloatingBottomBar = true,
                ),
                drivers = DriversUiState(canImport = false).withVerifiedList(emptyList()),
                packages = sortPackages(rawPackages, targets),
                isLoading = false,
                hasLoaded = true,
                moduleName = "Zygisk Module Sample",
                moduleVersion = "v0.0.0-dev",
                moduleAuthor = "NKU100",
            )
        }
    }

    private fun saveConfig(newConfig: ModuleConfig) {
        _uiState.update { it.copy(config = newConfig, themeMode = resolveThemeMode(newConfig)) }
        viewModelScope.launch {
            try {
                ConfigRepository.save(newConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformBridge.toast("Failed to save config: ${e.message}")
            }
        }
    }

    fun setEnabled(enabled: Boolean) =
        saveConfig(_uiState.value.config.copy(enabled = enabled))

    fun setThemeMode(mode: ThemeMode) =
        saveConfig(_uiState.value.config.copy(themeMode = mode.name))

    fun setEnableBlur(enabled: Boolean) =
        saveConfig(_uiState.value.config.copy(enableBlur = enabled))

    fun setEnableFloatingBottomBar(enabled: Boolean) =
        saveConfig(_uiState.value.config.copy(enableFloatingBottomBar = enabled))

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) =
        saveConfig(_uiState.value.config.copy(enableFloatingBottomBarBlur = enabled))

    fun setUpdateChannel(channel: UpdateChannel) {
        _uiState.update { it.copy(updateChannel = channel) }
        viewModelScope.launch { writeUpdateChannelToProp(channel) }
    }

    /**
     * Read and parse module.prop into a key-value map.
     * Each line is expected to be in `key=value` format.
     */
    private suspend fun readModuleProp(): Map<String, String> {
        return try {
            PlatformBridge.readFile(ModuleInfo.MODULE_PROP_PATH)
                .lines()
                .filter { '=' in it }
                .associate { line ->
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=').trim()
                    key to value
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Rewrite the updateJson line in module.prop by swapping the release path segment.
     * Stable ↔ Beta is a simple text replacement of the path portion.
     */
    private suspend fun writeUpdateChannelToProp(channel: UpdateChannel) {
        try {
            val content = PlatformBridge.readFile(ModuleInfo.MODULE_PROP_PATH)
            if (content.isBlank()) return
            val (from, to) = when (channel) {
                UpdateChannel.STABLE -> BETA_PATH to STABLE_PATH
                UpdateChannel.BETA -> STABLE_PATH to BETA_PATH
            }
            val newContent = content.lines().joinToString("\n") { line ->
                if (line.startsWith("updateJson=")) line.replace(from, to) else line
            }
            PlatformBridge.writeFile(ModuleInfo.MODULE_PROP_PATH, newContent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { /* best-effort */ }
    }

    companion object {
        private const val STABLE_PATH = "/releases/latest/download/"
        private const val BETA_PATH = "/releases/download/ci/"
    }

    fun toggleTargetPackage(packageName: String, enabled: Boolean) {
        val config = _uiState.value.config
        val newTargets = if (enabled) config.targetPackages + packageName
                         else config.targetPackages - packageName
        val newConfig = config.copy(targetPackages = newTargets)
        // Don't re-sort here — mirrors KSU behavior where sort order updates on next refresh,
        // preventing the list from jumping while user is on the AppProfile page.
        _uiState.update { it.copy(config = newConfig, themeMode = resolveThemeMode(newConfig)) }
        viewModelScope.launch {
            try {
                ConfigRepository.save(newConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformBridge.toast("Failed to save config: ${e.message}")
            }
        }
    }

    fun savePackageSettings(packageName: String, settings: PackageSettings) {
        val config = _uiState.value.config
        val currentDriverId = config.packageSettings[packageName]?.driverId.orEmpty()
        val newMap = config.packageSettings + (packageName to settings.copy(driverId = currentDriverId))
        saveConfig(config.copy(packageSettings = newMap))
    }

    fun setPackageDriver(packageName: String, driverId: String) {
        val current = _uiState.value
        val updated = current.config.withPackageDriver(packageName, driverId, current.drivers.drivers) ?: return
        if (updated == current.config || !hasPlatformApi()) return
        saveConfig(updated)
    }

    fun openDriverZipPicker(): Job = viewModelScope.launch {
        val current = _uiState.value.drivers
        if (current.isBusy || !current.canImport) return@launch
        _uiState.update {
            it.copy(drivers = it.drivers.copy(importError = null, importedDriver = null, deleteError = null,
                zipPicker = it.drivers.zipPicker.open()))
        }
        readDriverZipDirectory(DriverZipPickerState.DOWNLOADS_PATH)
    }

    fun browseDriverZipDirectory(path: String): Job = viewModelScope.launch {
        readDriverZipDirectory(path)
    }

    fun retryDriverZipDirectory(): Job = viewModelScope.launch {
        readDriverZipDirectory(_uiState.value.drivers.zipPicker.path)
    }

    fun selectDriverZip(entry: DriverPathEntry) {
        _uiState.update { it.copy(drivers = it.drivers.copy(zipPicker = it.drivers.zipPicker.select(entry))) }
    }

    fun closeDriverZipPicker() {
        _uiState.update { it.copy(drivers = it.drivers.copy(zipPicker = it.drivers.zipPicker.close())) }
    }

    fun importSelectedDriverZip(): Job = viewModelScope.launch {
        val path = _uiState.value.drivers.zipPicker.selectedZipPath ?: return@launch
        closeDriverZipPicker()
        importDriverZip(path)
    }

    private suspend fun readDriverZipDirectory(path: String) {
        val picker = _uiState.value.drivers.zipPicker
        if (!picker.isOpen || _uiState.value.drivers.isBusy) return
        _uiState.update { it.copy(drivers = it.drivers.copy(zipPicker = it.drivers.zipPicker.loading(path))) }
        try {
            val directory = DriverRepository.listZipDirectory(path)
            _uiState.update { it.copy(drivers = it.drivers.copy(zipPicker = it.drivers.zipPicker.loaded(directory))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriverPathException) {
            _uiState.update { it.copy(drivers = it.drivers.copy(zipPicker = it.drivers.zipPicker.failed(e.error))) }
        } catch (_: Exception) {
            _uiState.update {
                it.copy(drivers = it.drivers.copy(zipPicker = it.drivers.zipPicker.failed(DriverPathError.STORAGE_ERROR)))
            }
        }
    }

    private suspend fun importDriverZip(path: String) {
        val current = _uiState.value.drivers
        if (current.isBusy || !current.canImport) return
        _uiState.update { it.copy(drivers = it.drivers.copy(isBusy = true, importError = null, importedDriver = null, deleteError = null)) }
        try {
            when (val result = DriverRepository.importDriverZip(path)) {
                is DriverImportResult.Accepted -> {
                    _uiState.update { it.copy(drivers = it.drivers.afterSuccessfulImport(result.driver)) }
                    refreshDriverList()
                }
                is DriverImportResult.Rejected -> if (result.error != DriverArchiveError.CANCELLED) {
                    _uiState.update { it.copy(drivers = it.drivers.afterImportFailure(result.error)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update { it.copy(drivers = it.drivers.afterImportFailure(DriverArchiveError.STORAGE_ERROR)) }
        } finally {
            _uiState.update { it.copy(drivers = it.drivers.copy(isBusy = false)) }
        }
    }

    fun deleteDriver(driverId: String, resetBindings: Boolean = false): Job = viewModelScope.launch {
        val current = _uiState.value.drivers
        if (current.isBusy || (!resetBindings && current.isBound(driverId, _uiState.value.config))) return@launch
        _uiState.update {
            it.copy(drivers = current.copy(
                isBusy = true,
                importError = null,
                importedDriver = null,
                deleteError = null,
                deleteResetCount = 0,
            ))
        }
        try {
            val outcome = DriverRepository.deleteDriver(driverId, resetBindings)
            outcome.updatedConfig?.let { newConfig ->
                _uiState.update { it.copy(config = newConfig, themeMode = resolveThemeMode(newConfig)) }
            }
            when (val result = outcome.result) {
                DriverDeleteResult.DELETED -> {
                    _uiState.update { it.copy(drivers = it.drivers.afterSuccessfulDelete(driverId)) }
                    refreshDriverList()
                }
                else -> _uiState.update {
                    it.copy(drivers = it.drivers.copy(
                        deleteError = result,
                        deleteResetCount = outcome.resetPackageNames.size,
                    ))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update { it.copy(drivers = it.drivers.copy(deleteError = DriverDeleteResult.IO_ERROR)) }
        } finally {
            _uiState.update { it.copy(drivers = it.drivers.copy(isBusy = false)) }
        }
    }

    fun refreshDrivers(): Job = viewModelScope.launch {
        val current = _uiState.value.drivers
        if (current.isBusy || !current.canRetryList || !hasPlatformApi()) return@launch
        _uiState.update { it.copy(drivers = it.drivers.copy(isBusy = true)) }
        try {
            refreshDriverList()
        } finally {
            _uiState.update { it.copy(drivers = it.drivers.copy(isBusy = false)) }
        }
    }

    private suspend fun refreshDriverList() {
        try {
            val installed = DriverRepository.listDrivers()
            _uiState.update { it.copy(drivers = it.drivers.withVerifiedList(installed)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update { it.copy(drivers = it.drivers.afterListFailure()) }
        }
    }

    fun getPackageSettings(packageName: String): PackageSettings =
        _uiState.value.config.packageSettings[packageName] ?: PackageSettings()

    fun toggleShowSystemApps(): Job {
        val newValue = !_uiState.value.showSystemApps
        _uiState.update { it.copy(showSystemApps = newValue) }
        // Re-apply search with new filter setting
        return viewModelScope.launch {
            applySearchText(_uiState.value.appsSearchStatus.searchText)
        }
    }

    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.appsSearchStatus
        _uiState.update { it.copy(appsSearchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    private suspend fun applySearchText(text: String) {
        // Set LOAD status while computing
        _uiState.update {
            it.copy(
                appsSearchStatus = it.appsSearchStatus.copy(
                    resultStatus = searchLoadingStatusFor(text)
                )
            )
        }

        if (text.isEmpty()) {
            _uiState.update {
                it.copy(
                    searchResults = emptyList(),
                    appsSearchStatus = it.appsSearchStatus.copy(
                        resultStatus = SearchStatus.ResultStatus.DEFAULT
                    )
                )
            }
            return
        }

        val state = _uiState.value
        val targets = state.config.targetPackages.toSet()
        val sourceList = if (state.showSystemApps) state.packages
                         else state.packages.filter { !it.isSystemApp || it.packageName in targets }

        val result = withContext(Dispatchers.Default) {
            val filtered = sourceList.filter {
                it.label.contains(text, ignoreCase = true) ||
                    it.packageName.contains(text, ignoreCase = true)
            }
            sortPackages(filtered, targets)
        }

        _uiState.update {
            it.copy(
                searchResults = result,
                appsSearchStatus = it.appsSearchStatus.copy(
                    resultStatus = if (result.isEmpty()) SearchStatus.ResultStatus.EMPTY
                                   else SearchStatus.ResultStatus.SHOW
                )
            )
        }
    }

    private fun resolveThemeMode(config: ModuleConfig): ThemeMode =
        ThemeMode.entries.find { it.name == config.themeMode } ?: ThemeMode.FOLLOW_SYSTEM

    private fun searchLoadingStatusFor(text: String): SearchStatus.ResultStatus =
        if (text.isEmpty()) SearchStatus.ResultStatus.DEFAULT
        else SearchStatus.ResultStatus.LOAD

    /** Sort packages: targeted first (0), others last (1); within each group, sort by label. */
    private fun sortPackages(list: List<PackageInfo>, targetPackages: Set<String>): List<PackageInfo> =
        list.sortedWith(
            compareBy<PackageInfo> { if (it.packageName in targetPackages) 0 else 1 }
                .thenBy { it.label.lowercase() }
        )
}
