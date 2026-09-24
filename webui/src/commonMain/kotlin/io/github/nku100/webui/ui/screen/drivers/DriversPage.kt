package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nku100.webui.data.DriverArchiveError
import io.github.nku100.webui.data.DriverDeleteResult
import io.github.nku100.webui.data.DriverInfo
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
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
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
                        onClick = { if (state.canImport && !state.isBusy) onImport() },
                    )
                }
            }
            if (state.isBusy) {
                item { Text(stringResource(Res.string.driver_working), modifier = Modifier.padding(horizontal = 24.dp)) }
            }
            if (state.loadFailed) {
                item { Text(stringResource(Res.string.driver_list_error), modifier = Modifier.padding(horizontal = 24.dp), color = colorScheme.error) }
            }
            state.importError?.let { error ->
                item { Text(importErrorText(error), modifier = Modifier.padding(horizontal = 24.dp), color = colorScheme.error) }
            }
            state.deleteError?.let { error ->
                item { Text(deleteErrorText(error), modifier = Modifier.padding(horizontal = 24.dp), color = colorScheme.error) }
            }
            item { SmallTitle(text = stringResource(Res.string.installed_drivers)) }
            if (state.drivers.isEmpty()) {
                item { Text(stringResource(Res.string.no_drivers), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = colorScheme.onSurfaceVariantSummary) }
            }
            items(state.drivers.size) { index ->
                val driver = state.drivers[index]
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = driver.name,
                        summary = "${driver.libraryName} · ${driver.abi}",
                        onClick = { selectedDriverId = driver.driverId },
                    )
                }
            }
            item { Spacer(Modifier.height(bottomPadding)) }
        }
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
                TextButton(
                    enabled = !bound && !state.isBusy,
                    onClick = { selectedDriverId = null; confirmDeleteId = driver.driverId },
                ) { Text(stringResource(Res.string.delete_driver)) }
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
                TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(Res.string.cancel)) }
                TextButton(
                    enabled = !state.isBusy && !state.isBound(driver.driverId, config),
                    onClick = { confirmDeleteId = null; onDelete(driver.driverId) },
                ) { Text(stringResource(Res.string.delete_driver)) }
            }
        }
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
