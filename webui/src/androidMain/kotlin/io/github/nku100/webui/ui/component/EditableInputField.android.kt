package io.github.nku100.webui.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal actual fun EditableInputField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        maxLines = if (singleLine) 1 else 4,
        modifier = modifier,
    )
}

internal actual fun readEditableInputValue(fallback: String): String = fallback

internal actual fun hideEditableInput() = Unit
