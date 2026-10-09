package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverPathEntry
import io.github.nku100.webui.data.DriverRelease
import io.github.nku100.webui.data.DriverReleaseAsset
import io.github.nku100.webui.data.DriverSources
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.platform.PackageInfo
import io.github.nku100.webui.ui.util.rememberDefaultBlurBackdrop
import io.github.nku100.webui.ui.util.topBarDefaultWindowInsetsPadding
import io.github.nku100.webui.ui.util.topBarModifier
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import zygisk_module_webui_template.webui.generated.resources.*

@Composable
fun DriversPage(
    state: DriversUiState,
    config: ModuleConfig,
    packages: List<PackageInfo> = emptyList(),
    onBack: () -> Unit,
    onOpenZipPicker: () -> Unit,
    onBrowseZipDirectory: (String) -> Unit,
    onSelectZip: (DriverPathEntry) -> Unit,
    onRetryZipDirectory: () -> Unit,
    onCancelZipPicker: () -> Unit,
    onImportSelectedZip: () -> Unit,
    onDelete: (String, Boolean) -> Unit,
    onRetryList: () -> Unit,
    onAddRepository: suspend (String) -> Boolean,
    onAddDefaultRepositories: suspend () -> Boolean,
    onRemoveRepository: suspend (String) -> Boolean,
    onClearSourceError: () -> Unit,
    onToggleRepository: (String) -> Unit,
    onRetryRepository: (String) -> Unit,
    onDownloadAsset: (DriverReleaseAsset) -> Unit,
    onCancelDownload: () -> Unit,
    onDismissDownload: () -> Unit,
    bottomPadding: Dp,
    enableBlur: Boolean,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberDefaultBlurBackdrop(enableBlur)
    var selectedDriverId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var showDefaultRepositoriesDialog by remember { mutableStateOf(false) }
    var showAddRepositoryDialog by remember { mutableStateOf(false) }
    var addRepositoryInput by remember { mutableStateOf("") }
    var confirmDeleteRepository by remember { mutableStateOf<String?>(null) }
    var sourceActionBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selectedDriver = state.drivers.firstOrNull { it.driverId == selectedDriverId }
    val pendingDelete = state.drivers.firstOrNull { it.driverId == confirmDeleteId }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.topBarModifier(blurBackdrop),
                color = if (enableBlur) Color.Transparent else colorScheme.surface,
                title = stringResource(Res.string.drivers_title),
                navigationIcon = {
                    IconButton(modifier = Modifier.padding(start = 16.dp), onClick = onBack) {
                        Icon(MiuixIcons.Back, tint = colorScheme.onSurface, contentDescription = stringResource(Res.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = topBarDefaultWindowInsetsPadding,
            )
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .then(blurBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item { SmallTitle(text = stringResource(Res.string.driver_online_repositories)) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DriverPrimaryButton(
                            text = stringResource(Res.string.driver_add_default_repositories),
                            enabled = !sourceActionBusy && !state.isBusy,
                            onClick = { showDefaultRepositoriesDialog = true; onClearSourceError() },
                        )
                        DriverTextActionButton(
                            text = stringResource(Res.string.driver_add_repository),
                            enabled = !sourceActionBusy && !state.isBusy,
                            onClick = { addRepositoryInput = ""; showAddRepositoryDialog = true; onClearSourceError() },
                        )
                    }
                }
                state.sourceActionError?.let { error ->
                    item {
                        Text(
                            sourceActionErrorText(error),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            color = colorScheme.error,
                        )
                    }
                }
                state.repositories.forEachIndexed { repositoryIndex, repository ->
                    val repositoryKey = DriverSources.identity(repository) ?: repository.lowercase()
                    val isExpanded = state.expandedRepository?.let(DriverSources::identity) == repositoryKey
                    val releaseState = state.repositoryReleases[repositoryKey]
                    item(key = "driver-repository-$repositoryKey") {
                        val repositoryShape = repositoryShape(repositoryIndex, state.repositories.lastIndex)
                        Surface(
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 3.dp).fillMaxWidth(),
                            shape = repositoryShape,
                            color = colorScheme.surfaceContainer,
                        ) {
                            Column {
                                RepositorySourceItem(
                                    repository = repository,
                                    releaseState = releaseState,
                                    isExpanded = isExpanded,
                                    isEnabled = !state.isBusy && !sourceActionBusy,
                                    onToggle = { onToggleRepository(repository) },
                                    onDelete = { confirmDeleteRepository = repository; onClearSourceError() },
                                )
                                if (isExpanded) {
                                    when {
                                        releaseState?.isLoading == true -> LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        )
                                        releaseState?.hasError == true -> Column(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        ) {
                                            Text(stringResource(Res.string.driver_release_load_failed), color = colorScheme.error)
                                            DriverTextActionButton(
                                                text = stringResource(Res.string.retry_driver_list),
                                                onClick = { onRetryRepository(repository) },
                                            )
                                        }
                                        releaseState?.hasLoaded == true && releaseState.releases.isEmpty() -> Text(
                                            stringResource(Res.string.driver_release_empty),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            color = colorScheme.onSurfaceVariantSummary,
                                        )
                                        releaseState?.hasLoaded == true -> Column(
                                            modifier = Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, bottom = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(7.dp),
                                        ) {
                                            releaseState.releases.forEach { release ->
                                                DriverReleaseCard(
                                                    release = release,
                                                    enabled = !state.isBusy,
                                                    onDownload = onDownloadAsset,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(Res.string.import_driver_zip),
                            summary = if (state.canImport) stringResource(Res.string.import_driver_summary)
                                else stringResource(Res.string.driver_preview_unavailable),
                            onClick = { if (state.canImport && !state.isBusy) onOpenZipPicker() },
                        )
                    }
                }
                if (state.isBusy || state.listStatus == DriverListStatus.LOADING) {
                    item { Text(stringResource(Res.string.driver_working), modifier = Modifier.padding(horizontal = 24.dp)) }
                }
                if (state.canRetryList) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                            Text(
                                if (state.listStatus == DriverListStatus.UNAVAILABLE) stringResource(Res.string.driver_list_error)
                                else stringResource(Res.string.driver_list_stale),
                                color = colorScheme.error,
                            )
                            DriverTextActionButton(
                                text = stringResource(Res.string.retry_driver_list),
                                enabled = !state.isBusy,
                                onClick = onRetryList,
                            )
                        }
                    }
                }
                state.importError?.let { error ->
                    item { Text(importErrorText(error), modifier = Modifier.padding(horizontal = 24.dp), color = colorScheme.error) }
                }
                state.importedDriver?.let { driver ->
                    item {
                        Text(
                            stringResource(Res.string.driver_import_success, driver.name),
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                state.deleteError?.let { error ->
                    item {
                        Text(
                            deleteErrorText(error, state.deleteResetCount),
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = colorScheme.error,
                        )
                    }
                }
                item { SmallTitle(text = stringResource(Res.string.installed_drivers)) }
                if (state.showEmptyState) {
                    item { Text(stringResource(Res.string.no_drivers), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = colorScheme.onSurfaceVariantSummary) }
                }
                items(state.drivers.size) { index ->
                    val driver = state.drivers[index]
                    val driverShape = repositoryShape(index, state.drivers.lastIndex)
                    DriverListItem(
                        driver = driver,
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 3.dp).fillMaxWidth(),
                        shape = driverShape,
                    ) {
                        selectedDriverId = it
                    }
                }
                item { Spacer(Modifier.height(bottomPadding)) }
            }

            selectedDriver?.let { driver ->
                val bound = config.driverBindings(driver.driverId, packages).isNotEmpty()
                OverlayDialog(
                    title = driver.name,
                    show = true,
                    onDismissRequest = { selectedDriverId = null },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        DetailLine(stringResource(Res.string.driver_library), driver.libraryName)
                        DetailLine(stringResource(Res.string.driver_abi), driver.abi)
                        DetailLine(
                            stringResource(Res.string.driver_imported_at),
                            if (driver.importedAtEpochMillis > 0) Instant.fromEpochMilliseconds(driver.importedAtEpochMillis).toString()
                            else stringResource(Res.string.driver_unknown),
                        )
                        DetailLine(stringResource(Res.string.driver_hash), driver.archiveSha256.take(12).ifEmpty { stringResource(Res.string.driver_unknown) })
                        if (bound) {
                            Text(stringResource(Res.string.driver_bound_will_reset), fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().height(38.dp).testTag("driver-delete-entry"),
                            enabled = !state.isBusy,
                            onClick = { selectedDriverId = null; confirmDeleteId = driver.driverId },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                            border = BorderStroke(1.dp, colorScheme.error),
                        ) {
                            Icon(MiuixIcons.Delete, modifier = Modifier.size(20.dp), tint = colorScheme.error, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.delete_driver), style = MiuixTheme.textStyles.body2, color = colorScheme.error)
                        }
                    }
                }
            }

            pendingDelete?.let { driver ->
                val bindings = config.driverBindings(driver.driverId, packages)
                val resetBindings = bindings.isNotEmpty()
                OverlayDialog(
                    title = stringResource(Res.string.delete_driver),
                    show = true,
                    onDismissRequest = { confirmDeleteId = null },
                ) {
                    Column {
                        Text(
                            when {
                                bindings.size == 1 -> stringResource(
                                    Res.string.confirm_delete_driver_with_reset_one,
                                    driver.name,
                                )
                                resetBindings -> stringResource(
                                    Res.string.confirm_delete_driver_with_reset_many,
                                    driver.name,
                                    bindings.size,
                                )
                                else -> stringResource(Res.string.confirm_delete_driver, driver.name)
                            },
                        )
                        if (resetBindings) {
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 12.dp, bottom = 4.dp),
                            ) {
                                bindings.forEach { binding ->
                                    DriverBindingItem(binding)
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DriverTextActionButton(
                                text = stringResource(if (resetBindings) Res.string.driver_delete_and_reset else Res.string.delete_driver),
                                modifier = Modifier.fillMaxWidth().testTag("driver-delete-confirm"),
                                onClick = { confirmDeleteId = null; onDelete(driver.driverId, resetBindings) },
                                enabled = !state.isBusy,
                                destructive = true,
                            )
                            DriverCancelDeleteButton(
                                modifier = Modifier.testTag("driver-delete-cancel"),
                                onClick = { confirmDeleteId = null },
                            )
                        }
                    }
                }
            }

            if (showDefaultRepositoriesDialog) {
                OverlayDialog(
                    title = stringResource(Res.string.driver_confirm_add_defaults),
                    show = true,
                    onDismissRequest = { showDefaultRepositoriesDialog = false; onClearSourceError() },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.driver_confirm_add_defaults_summary))
                        state.sourceActionError?.let { error ->
                            Text(sourceActionErrorText(error), color = colorScheme.error, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DriverTextActionButton(
                                text = stringResource(Res.string.cancel),
                                onClick = { showDefaultRepositoriesDialog = false },
                                enabled = !sourceActionBusy,
                            )
                            DriverPrimaryButton(
                                text = stringResource(Res.string.driver_add_default_repositories),
                                enabled = !sourceActionBusy,
                                onClick = {
                                    scope.launch {
                                        sourceActionBusy = true
                                        try {
                                            val added = onAddDefaultRepositories()
                                            if (added) showDefaultRepositoriesDialog = false
                                        } finally {
                                            sourceActionBusy = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (showAddRepositoryDialog) {
                OverlayDialog(
                    title = stringResource(Res.string.driver_add_repository_title),
                    show = true,
                    onDismissRequest = { showAddRepositoryDialog = false; onClearSourceError() },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MiuixTextField(
                            value = addRepositoryInput,
                            onValueChange = { addRepositoryInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(Res.string.driver_repository_url),
                            enabled = !sourceActionBusy,
                        )
                        state.sourceActionError?.let { error ->
                            Text(sourceActionErrorText(error), color = colorScheme.error, modifier = Modifier.padding(top = 6.dp), fontSize = 12.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DriverTextActionButton(
                                text = stringResource(Res.string.cancel),
                                onClick = { showAddRepositoryDialog = false; onClearSourceError() },
                                enabled = !sourceActionBusy,
                            )
                            DriverPrimaryButton(
                                text = stringResource(Res.string.driver_add_repository),
                                enabled = !sourceActionBusy,
                                onClick = {
                                    scope.launch {
                                        sourceActionBusy = true
                                        onClearSourceError()
                                        try {
                                            val added = onAddRepository(addRepositoryInput)
                                            if (added) showAddRepositoryDialog = false
                                        } finally {
                                            sourceActionBusy = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            confirmDeleteRepository?.let { repository ->
                OverlayDialog(
                    title = stringResource(Res.string.driver_confirm_delete_repository),
                    show = true,
                    onDismissRequest = { confirmDeleteRepository = null; onClearSourceError() },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.driver_confirm_delete_repository_summary))
                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DriverTextActionButton(
                                text = stringResource(Res.string.driver_remove_repository),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !sourceActionBusy,
                                destructive = true,
                                onClick = {
                                    scope.launch {
                                        sourceActionBusy = true
                                        try {
                                            val removed = onRemoveRepository(repository)
                                            if (removed) confirmDeleteRepository = null
                                        } finally {
                                            sourceActionBusy = false
                                        }
                                    }
                                },
                            )
                            DriverCancelDeleteButton(
                                enabled = !sourceActionBusy,
                                onClick = { confirmDeleteRepository = null },
                            )
                        }
                    }
                }
            }

            state.download?.let { download ->
                OverlayDialog(
                    title = stringResource(Res.string.driver_download_title),
                    show = true,
                    onDismissRequest = {
                        if (!download.isImporting) {
                            if (download.error == null) onCancelDownload() else onDismissDownload()
                        }
                    },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(download.asset.name, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        when {
                            download.error != null -> Text(
                                downloadErrorText(download.error),
                                color = colorScheme.error,
                            )
                            download.isImporting -> {
                                InfiniteProgressIndicator()
                                Text(stringResource(Res.string.driver_download_validating), modifier = Modifier.padding(top = 8.dp), color = colorScheme.onSurfaceVariantSummary)
                            }
                            else -> {
                                val fraction = if (download.totalBytes > 0) {
                                    (download.downloadedBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                Text(
                                    stringResource(Res.string.driver_download_progress, formatDriverBytes(download.downloadedBytes), formatDriverBytes(download.totalBytes)),
                                    modifier = Modifier.padding(top = 8.dp),
                                    color = colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            DriverTextActionButton(text = stringResource(
                                if (download.error == null) Res.string.driver_download_cancel else Res.string.driver_download_close,
                            ), enabled = !download.isImporting, onClick = {
                                if (download.error == null) onCancelDownload() else onDismissDownload()
                            })
                        }
                    }
                }
            }

            DriverZipPickerDialog(
                state = state,
                onBrowseDirectory = onBrowseZipDirectory,
                onSelectZip = onSelectZip,
                onRetry = onRetryZipDirectory,
                onCancel = onCancelZipPicker,
                onImport = onImportSelectedZip,
            )
        }
    }
}

@Composable
private fun DriverCancelDeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        modifier = modifier.fillMaxWidth().height(38.dp),
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurface),
        border = BorderStroke(1.dp, colorScheme.onSurfaceVariantSummary),
    ) {
        Text(stringResource(Res.string.cancel), style = MiuixTheme.textStyles.body2, color = colorScheme.onSurface)
    }
}

@Composable
internal fun DriverPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MiuixButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        cornerRadius = 18.dp,
        minWidth = 48.dp,
        minHeight = 38.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = MiuixButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text, fontSize = 15.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun DriverTextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    MiuixTextButton(
        text = text,
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        cornerRadius = 18.dp,
        minWidth = 48.dp,
        minHeight = 38.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        textStyle = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
        colors = if (destructive) {
            MiuixButtonDefaults.textButtonColors(
                color = Color.Transparent,
                disabledColor = Color.Transparent,
                textColor = colorScheme.error,
                disabledTextColor = colorScheme.onSurfaceVariantSummary,
            )
        } else {
            MiuixButtonDefaults.textButtonColors()
        },
    )
}

@Composable
private fun RepositorySourceItem(
    repository: String,
    releaseState: DriverRepositoryReleases?,
    isExpanded: Boolean,
    isEnabled: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val subtitle = when {
        releaseState?.isLoading == true -> stringResource(Res.string.driver_release_loading)
        releaseState?.hasError == true -> stringResource(Res.string.driver_repository_retry_hint)
        releaseState?.hasLoaded != true -> stringResource(Res.string.driver_repository_expand_hint)
        releaseState.releases.isEmpty() -> stringResource(Res.string.driver_repository_no_downloads)
        else -> {
            val latestRelease = releaseState.releases.maxByOrNull { it.publishedAt }
            if (latestRelease == null) {
                stringResource(Res.string.driver_repository_no_downloads)
            } else {
                buildList {
                    add(latestRelease.tag)
                    latestRelease.publishedAt.substringBefore('T')
                        .takeIf(String::isNotBlank)
                        ?.let(::add)
                    add(stringResource(Res.string.driver_repository_zip_count, latestRelease.assets.size))
                    if (latestRelease.isPrerelease) {
                        add(stringResource(Res.string.driver_release_prerelease))
                    }
                }.joinToString(" · ")
            }
        }
    }

    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        endActions = {
            IconButton(onClick = onToggle, enabled = isEnabled, minWidth = 42.dp, minHeight = 42.dp, cornerRadius = 21.dp) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    modifier = Modifier.size(20.dp),
                    tint = colorScheme.primary,
                    contentDescription = stringResource(if (isExpanded) Res.string.driver_release_collapse else Res.string.driver_release_expand),
                )
            }
            IconButton(onClick = onDelete, enabled = isEnabled, minWidth = 42.dp, minHeight = 42.dp, cornerRadius = 21.dp) {
                Icon(
                    MiuixIcons.Delete,
                    modifier = Modifier.size(20.dp),
                    tint = colorScheme.error,
                    contentDescription = stringResource(Res.string.driver_delete_repository_accessibility),
                )
            }
        },
    ) {
        Text(
            repository,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onBackground,
            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
            overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

private fun repositoryShape(index: Int, lastIndex: Int): RoundedCornerShape = when {
    lastIndex == 0 -> RoundedCornerShape(18.dp)
    index == 0 -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 6.dp)
    index == lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    else -> RoundedCornerShape(6.dp)
}

@Composable
private fun DriverReleaseCard(
    release: DriverRelease,
    enabled: Boolean,
    onDownload: (DriverReleaseAsset) -> Unit,
) {
    var notesExpanded by remember(release.repository, release.tag) { mutableStateOf(false) }
    var notesOverflow by remember(release.repository, release.tag) { mutableStateOf(false) }
    val notes = release.notes.trim()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(release.title, modifier = Modifier.weight(1f), fontSize = 16.sp, lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    release.publishedAt.substringBefore('T'),
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            if (notes.isNotEmpty()) {
                Text(
                    text = notes,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = if (notesExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layoutResult ->
                        if (!notesExpanded) notesOverflow = layoutResult.hasVisualOverflow
                    },
                )
                if (notesOverflow || notesExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 38.dp)
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Button,
                            ) { notesExpanded = !notesExpanded },
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(if (notesExpanded) Res.string.driver_release_collapse else Res.string.driver_release_expand),
                            color = colorScheme.primary,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
            release.assets.forEachIndexed { index, asset ->
                if (index > 0) {
                    Spacer(
                        Modifier.fillMaxWidth().height(1.dp).background(colorScheme.surfaceContainer),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        asset.name,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDriverBytes(asset.sizeBytes),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    IconButton(enabled = enabled, onClick = { onDownload(asset) }, minWidth = 42.dp, minHeight = 42.dp, cornerRadius = 21.dp) {
                        Icon(
                            Icons.Default.FileDownload,
                            modifier = Modifier.size(20.dp),
                            tint = colorScheme.primary,
                            contentDescription = stringResource(Res.string.driver_download_install),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sourceActionErrorText(error: DriverSourceActionError): String = when (error) {
    DriverSourceActionError.INVALID_REPOSITORY -> stringResource(Res.string.driver_invalid_repository)
    DriverSourceActionError.REPOSITORY_EXISTS -> stringResource(Res.string.driver_repository_exists)
    DriverSourceActionError.SAVE_FAILED -> stringResource(Res.string.driver_repository_save_failed)
}

@Composable
private fun downloadErrorText(error: DriverArchiveError): String = when (error) {
    DriverArchiveError.TRANSFER_FAILED -> stringResource(Res.string.driver_download_failed)
    else -> importErrorText(error)
}

private fun formatDriverBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

@Composable
private fun DriverBindingItem(binding: DriverAppBinding) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(binding.label, fontSize = 14.sp)
        Text(binding.packageName, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        Text(
            modifier = Modifier.testTag("driver-binding-status-${binding.packageName}"),
            text = stringResource(if (binding.isModuleEnabled) Res.string.driver_binding_enabled else Res.string.driver_binding_disabled),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
internal fun DriverListItem(
    driver: DriverInfo,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    onClick: (String) -> Unit,
) {
    val showDriverDetails = { onClick(driver.driverId) }
    Surface(
        modifier = modifier,
        shape = shape,
        color = colorScheme.surfaceContainer,
    ) {
        ArrowPreference(
            title = driver.name,
            summary = "${driver.libraryName} · ${driver.abi}",
            onClick = showDriverDetails,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text("$label: $value", modifier = Modifier.padding(bottom = 8.dp), fontSize = 14.sp)
}

@Composable
private fun importErrorText(error: DriverArchiveError): String = when (error) {
    DriverArchiveError.CANCELLED -> stringResource(Res.string.cancel)
    DriverArchiveError.TRANSFER_FAILED, DriverArchiveError.STORAGE_ERROR -> stringResource(Res.string.driver_storage_error)
    DriverArchiveError.UNSUPPORTED_ABI -> stringResource(Res.string.driver_unsupported_abi)
    else -> stringResource(Res.string.driver_invalid_zip)
}

@Composable
private fun deleteErrorText(error: DriverDeleteResult, resetCount: Int): String = if (resetCount > 0) {
    if (resetCount == 1) stringResource(Res.string.driver_delete_error_after_reset_one)
    else stringResource(Res.string.driver_delete_error_after_reset_many, resetCount)
} else when (error) {
    DriverDeleteResult.BOUND -> stringResource(Res.string.driver_bound_cannot_delete)
    DriverDeleteResult.NOT_FOUND -> stringResource(Res.string.driver_not_found)
    DriverDeleteResult.INVALID_ID, DriverDeleteResult.IO_ERROR -> stringResource(Res.string.driver_delete_error)
    DriverDeleteResult.DELETED -> ""
}
