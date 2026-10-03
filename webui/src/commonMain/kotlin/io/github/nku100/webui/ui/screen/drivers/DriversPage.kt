package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
import io.github.nku100.webui.data.DriverPathEntry
import io.github.nku100.webui.data.ModuleConfig
import io.github.nku100.webui.ui.util.rememberDefaultBlurBackdrop
import io.github.nku100.webui.ui.util.topBarDefaultWindowInsetsPadding
import io.github.nku100.webui.ui.util.topBarModifier
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import zygisk_module_webui_template.webui.generated.resources.*

@Composable
fun DriversPage(
    state: DriversUiState,
    config: ModuleConfig,
    onBack: () -> Unit,
    onOpenZipPicker: () -> Unit,
    onBrowseZipDirectory: (String) -> Unit,
    onSelectZip: (DriverPathEntry) -> Unit,
    onRetryZipDirectory: () -> Unit,
    onCancelZipPicker: () -> Unit,
    onImportSelectedZip: () -> Unit,
    onDelete: (String) -> Unit,
    onRetryList: () -> Unit,
    bottomPadding: Dp,
    enableBlur: Boolean,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurBackdrop = rememberDefaultBlurBackdrop(enableBlur)
    var selectedDriverId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
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
                            TextButton(enabled = !state.isBusy, onClick = onRetryList) {
                                Text(stringResource(Res.string.retry_driver_list))
                            }
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
                    item { Text(deleteErrorText(error), modifier = Modifier.padding(horizontal = 24.dp), color = colorScheme.error) }
                }
                item { SmallTitle(text = stringResource(Res.string.installed_drivers)) }
                if (state.showEmptyState) {
                    item { Text(stringResource(Res.string.no_drivers), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = colorScheme.onSurfaceVariantSummary) }
                }
                items(state.drivers.size) { index ->
                    val driver = state.drivers[index]
                    DriverListItem(driver, Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth()) {
                        selectedDriverId = it
                    }
                }
                item { Spacer(Modifier.height(bottomPadding)) }
            }

            selectedDriver?.let { driver ->
                val bound = state.isBound(driver.driverId, config)
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
                            Text(stringResource(Res.string.driver_bound_cannot_delete), fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("driver-delete-entry"),
                            enabled = !bound && !state.isBusy,
                            onClick = { selectedDriverId = null; confirmDeleteId = driver.driverId },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                            border = BorderStroke(1.dp, colorScheme.error),
                        ) {
                            Icon(MiuixIcons.Delete, tint = colorScheme.error, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.delete_driver), color = colorScheme.error)
                        }
                    }
                }
            }

            pendingDelete?.let { driver ->
                OverlayDialog(
                    title = stringResource(Res.string.delete_driver),
                    show = true,
                    onDismissRequest = { confirmDeleteId = null },
                ) {
                    Column {
                        Text(stringResource(Res.string.confirm_delete_driver, driver.name))
                        TextButton(
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("driver-delete-confirm"),
                            onClick = { confirmDeleteId = null; onDelete(driver.driverId) },
                            enabled = !state.isBusy && !state.isBound(driver.driverId, config),
                            colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error),
                        ) {
                            Text(stringResource(Res.string.delete_driver), color = colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("driver-delete-cancel"),
                            onClick = { confirmDeleteId = null },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.onSurface),
                            border = BorderStroke(1.dp, colorScheme.onSurfaceVariantSummary),
                        ) {
                            Text(stringResource(Res.string.cancel), color = colorScheme.onSurface)
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
internal fun DriverListItem(
    driver: DriverInfo,
    modifier: Modifier = Modifier,
    onClick: (String) -> Unit,
) {
    val showDriverDetails = { onClick(driver.driverId) }
    Card(
        modifier = modifier,
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
private fun deleteErrorText(error: DriverDeleteResult): String = when (error) {
    DriverDeleteResult.BOUND -> stringResource(Res.string.driver_bound_cannot_delete)
    DriverDeleteResult.NOT_FOUND -> stringResource(Res.string.driver_not_found)
    DriverDeleteResult.INVALID_ID, DriverDeleteResult.IO_ERROR -> stringResource(Res.string.driver_delete_error)
    DriverDeleteResult.DELETED -> ""
}
