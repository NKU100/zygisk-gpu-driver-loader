package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.nku100.webui.data.DriverPathError
import io.github.nku100.webui.data.DriverPathEntry
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import zygisk_module_webui_template.webui.generated.resources.Res
import zygisk_module_webui_template.webui.generated.resources.back
import zygisk_module_webui_template.webui.generated.resources.cancel
import zygisk_module_webui_template.webui.generated.resources.driver_picker_access_denied
import zygisk_module_webui_template.webui.generated.resources.driver_picker_empty
import zygisk_module_webui_template.webui.generated.resources.driver_picker_folder_accessibility
import zygisk_module_webui_template.webui.generated.resources.driver_picker_import
import zygisk_module_webui_template.webui.generated.resources.driver_picker_invalid_path
import zygisk_module_webui_template.webui.generated.resources.driver_picker_loading
import zygisk_module_webui_template.webui.generated.resources.driver_picker_retry
import zygisk_module_webui_template.webui.generated.resources.driver_picker_selected_accessibility
import zygisk_module_webui_template.webui.generated.resources.driver_picker_storage_error
import zygisk_module_webui_template.webui.generated.resources.driver_picker_storage_root
import zygisk_module_webui_template.webui.generated.resources.driver_picker_title
import zygisk_module_webui_template.webui.generated.resources.driver_picker_zip_accessibility
import zygisk_module_webui_template.webui.generated.resources.driver_working

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
            BreadcrumbHeader(picker, onBrowseDirectory, onCancel)
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
                    MaterialTextButton(onClick = onRetry) { Text(stringResource(Res.string.driver_picker_retry)) }
                }
                else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (!picker.isAtStorageRoot) {
                        DriverPathRow(
                            name = "..",
                            isDirectory = true,
                            contentDescription = stringResource(Res.string.driver_picker_folder_accessibility, ".."),
                            enabled = !state.isBusy,
                            onClick = { picker.parentPath()?.let(onBrowseDirectory) },
                        )
                    }
                    if (picker.entries.isEmpty()) {
                        Text(
                            stringResource(Res.string.driver_picker_empty),
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(picker.entries, key = { it.name }) { entry ->
                                val selected = picker.selectedFileName == entry.name
                                val description = when {
                                    entry.isDirectory -> stringResource(Res.string.driver_picker_folder_accessibility, entry.name)
                                    selected -> stringResource(Res.string.driver_picker_selected_accessibility, entry.name)
                                    else -> stringResource(Res.string.driver_picker_zip_accessibility, entry.name)
                                }
                                DriverPathRow(
                                    name = entry.name,
                                    isDirectory = entry.isDirectory,
                                    isSelected = selected,
                                    contentDescription = description,
                                    enabled = !state.isBusy,
                                    onClick = {
                                        if (entry.isDirectory) picker.childPath(entry)?.let(onBrowseDirectory)
                                        else onSelectZip(entry)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixTextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = onCancel,
                    enabled = !state.isBusy,
                    minWidth = 88.dp,
                    minHeight = ACTION_BUTTON_HEIGHT,
                    cornerRadius = ACTION_BUTTON_HEIGHT / 2,
                    insideMargin = ACTION_BUTTON_PADDING,
                    colors = ButtonDefaults.textButtonColors(),
                )
                MiuixButton(
                    onClick = onImport,
                    enabled = picker.selectedZipPath != null && !picker.isLoading && picker.error == null && !state.isBusy,
                    minWidth = 88.dp,
                    minHeight = ACTION_BUTTON_HEIGHT,
                    cornerRadius = ACTION_BUTTON_HEIGHT / 2,
                    insideMargin = ACTION_BUTTON_PADDING,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(if (state.isBusy) Res.string.driver_working else Res.string.driver_picker_import))
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbHeader(
    picker: DriverZipPickerState,
    onBrowseDirectory: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val parent = picker.parentPath()
        IconButton(
            onClick = { parent?.let(onBrowseDirectory) ?: onCancel() },
            enabled = !picker.isAtStorageRoot,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.back),
                tint = if (picker.isAtStorageRoot) colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                    else colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val paths = picker.breadcrumbPaths()
            paths.forEachIndexed { index, path ->
                if (index > 0) {
                    Text("›", color = colorScheme.onSurfaceVariantSummary, fontSize = 18.sp)
                }
                val isCurrent = index == paths.lastIndex
                val label = if (index == 0) {
                    stringResource(Res.string.driver_picker_storage_root)
                } else {
                    path.substringAfterLast('/')
                }
                Box(
                    Modifier.defaultMinSize(minHeight = BREADCRUMB_TOUCH_HEIGHT)
                        .clickable(role = Role.Button) { onBrowseDirectory(path) }
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (isCurrent) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverPathRow(
    name: String,
    isDirectory: Boolean,
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(PATH_ROW_CORNER_RADIUS)
    val highlight = if (isHovered || isPressed) colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent

    Row(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(highlight)
            .hoverable(interactionSource, enabled = enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.selected = isSelected
            }
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .heightIn(min = PATH_ROW_CONTENT_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colorScheme.onSurface,
        )
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val ACTION_BUTTON_HEIGHT = 44.dp
private val ACTION_BUTTON_PADDING = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
private val BREADCRUMB_TOUCH_HEIGHT = 44.dp
private val PATH_ROW_CONTENT_HEIGHT = 24.dp
private val PATH_ROW_CORNER_RADIUS = 8.dp
