package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.nku100.webui.data.DriverPathError
import io.github.nku100.webui.data.DriverPathEntry
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import zygisk_module_webui_template.webui.generated.resources.Res
import zygisk_module_webui_template.webui.generated.resources.cancel
import zygisk_module_webui_template.webui.generated.resources.driver_working
import zygisk_module_webui_template.webui.generated.resources.driver_picker_access_denied
import zygisk_module_webui_template.webui.generated.resources.driver_picker_empty
import zygisk_module_webui_template.webui.generated.resources.driver_picker_folder
import zygisk_module_webui_template.webui.generated.resources.driver_picker_folder_accessibility
import zygisk_module_webui_template.webui.generated.resources.driver_picker_import
import zygisk_module_webui_template.webui.generated.resources.driver_picker_invalid_path
import zygisk_module_webui_template.webui.generated.resources.driver_picker_loading
import zygisk_module_webui_template.webui.generated.resources.driver_picker_retry
import zygisk_module_webui_template.webui.generated.resources.driver_picker_selected_accessibility
import zygisk_module_webui_template.webui.generated.resources.driver_picker_storage_error
import zygisk_module_webui_template.webui.generated.resources.driver_picker_storage_root
import zygisk_module_webui_template.webui.generated.resources.driver_picker_title
import zygisk_module_webui_template.webui.generated.resources.driver_picker_zip
import zygisk_module_webui_template.webui.generated.resources.driver_picker_zip_accessibility

@Composable
internal fun DriverZipPickerDialog(
    state: DriversUiState,
    onBrowseDirectory: (String) -> Unit,
    onSelectZip: (DriverPathEntry) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    val picker = state.zipPicker
    if (!picker.isOpen) return

    OverlayDialog(
        title = stringResource(Res.string.driver_picker_title),
        show = true,
        onDismissRequest = onCancel,
    ) {
        val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = true,
            onBackCompleted = {
                picker.parentPath()?.let(onBrowseDirectory) ?: onCancel()
            },
        )

        Column(Modifier.fillMaxWidth().widthIn(max = 520.dp).heightIn(min = 220.dp, max = 600.dp)) {
            Breadcrumbs(picker.path, onBrowseDirectory)
            Spacer(Modifier.height(8.dp))
            when {
                picker.isLoading -> Text(stringResource(Res.string.driver_picker_loading), color = colorScheme.onSurfaceVariantSummary)
                picker.error != null -> {
                    Text(
                        when (picker.error) {
                            DriverPathError.INVALID_PATH -> stringResource(Res.string.driver_picker_invalid_path)
                            DriverPathError.ACCESS_DENIED -> stringResource(Res.string.driver_picker_access_denied)
                            DriverPathError.STORAGE_ERROR -> stringResource(Res.string.driver_picker_storage_error)
                        },
                        color = colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.driver_picker_retry)) }
                }
                picker.entries.isEmpty() -> Text(stringResource(Res.string.driver_picker_empty), color = colorScheme.onSurfaceVariantSummary)
                else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (!picker.isAtStorageRoot) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { picker.parentPath()?.let(onBrowseDirectory) },
                        ) {
                            Text("..  ${stringResource(Res.string.driver_picker_folder)}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(picker.entries.size, key = { picker.entries[it].name }) { index ->
                            val entry = picker.entries[index]
                            val selected = picker.selectedFileName == entry.name
                            val description = when {
                                entry.isDirectory -> stringResource(Res.string.driver_picker_folder_accessibility, entry.name)
                                selected -> stringResource(Res.string.driver_picker_selected_accessibility, entry.name)
                                else -> stringResource(Res.string.driver_picker_zip_accessibility, entry.name)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = description
                                        this.selected = selected
                                    }
                                    .clickable(enabled = !state.isBusy) {
                                        if (entry.isDirectory) picker.childPath(entry)?.let(onBrowseDirectory)
                                        else onSelectZip(entry)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(if (entry.isDirectory) Res.string.driver_picker_folder else Res.string.driver_picker_zip),
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                if (selected) Text("✓", color = colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, enabled = !state.isBusy) {
                    Text(stringResource(Res.string.cancel))
                }
                Button(
                    onClick = onImport,
                    enabled = picker.selectedZipPath != null && !picker.isLoading && picker.error == null && !state.isBusy,
                ) {
                    Text(stringResource(if (state.isBusy) Res.string.driver_working else Res.string.driver_picker_import))
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onBrowseDirectory: (String) -> Unit) {
    val relative = path.removePrefix(DriverZipPickerState.STORAGE_ROOT).trim('/')
    val segments = relative.split('/').filter(String::isNotEmpty)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        var accumulated = DriverZipPickerState.STORAGE_ROOT
        TextButton(onClick = { onBrowseDirectory(accumulated) }) {
            Text(stringResource(Res.string.driver_picker_storage_root))
        }
        segments.forEach { segment ->
            accumulated = "$accumulated/$segment"
            val target = accumulated
            Text("/")
            TextButton(onClick = { onBrowseDirectory(target) }) { Text(segment, maxLines = 1) }
        }
    }
}
