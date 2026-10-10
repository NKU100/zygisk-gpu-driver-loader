package io.github.nku100.webui.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.js.JsString
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private var inputBridgeVisible = false

@JsFun(
    """(left, top, width, height, value, surface, text, primary, multiline) => {
        const id = 'miuix-text-input-bridge';
        let field = document.getElementById(id);
        if (!field) {
            field = document.createElement(multiline ? 'textarea' : 'input');
            field.id = id;
            document.body.append(field);
            field.addEventListener('focus', () => {
                field.style.border = '2px solid ' + field.dataset.primary;
            });
            field.addEventListener('blur', () => {
                field.style.border = '2px solid transparent';
            });
        }
        if (!multiline) field.type = 'text';
        field.value = value;
        field.dataset.primary = primary;
        if (multiline) { field.rows = 4; field.wrap = 'soft'; }
        Object.assign(field.style, {
            position: 'fixed', left: left + 'px', top: top + 'px',
            width: width + 'px', height: height + 'px', boxSizing: 'border-box',
            margin: '0', padding: '12px 16px', border: '2px solid transparent',
            borderRadius: '16px', outline: 'none', resize: 'none',
            background: surface, color: text, caretColor: primary,
            fontFamily: 'Roboto, sans-serif', fontSize: '16px', lineHeight: '1.4',
            zIndex: '2147483647', WebkitTapHighlightColor: 'transparent'
        });
    }"""
)
private external fun showMiuixTextInput(
    left: Double,
    top: Double,
    width: Double,
    height: Double,
    value: String,
    surface: String,
    text: String,
    primary: String,
    multiline: Boolean,
)

@JsFun("() => document.getElementById('miuix-text-input-bridge')?.value ?? ''")
private external fun readMiuixTextInput(): JsString

@JsFun("() => { const field = document.getElementById('miuix-text-input-bridge'); field?.blur(); field?.remove(); }")
private external fun removeMiuixTextInput()

@JsFun("(left, top, width, height) => { const field = document.getElementById('miuix-text-input-bridge'); if (field) Object.assign(field.style, {left: left + 'px', top: top + 'px', width: width + 'px', height: height + 'px'}); }")
private external fun updateMiuixTextInputBounds(left: Double, top: Double, width: Double, height: Double)

@JsFun("(left, top) => { const canvas = document.querySelector('canvas'); const rect = canvas?.getBoundingClientRect(); return (rect ? rect.left + left : left) + ',' + (rect ? rect.top + top : top); }")
private external fun miuixInputPositionInViewport(left: Double, top: Double): JsString

@Composable
internal actual fun EditableInputField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current.density
    val colors = MiuixTheme.colorScheme

    Box(modifier = modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            maxLines = if (singleLine) 1 else 4,
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    if (!enabled) return@onGloballyPositioned

                    val position = coordinates.positionInRoot()
                    val viewportPosition = miuixInputPositionInViewport(
                        (position.x / density).toDouble(),
                        (position.y / density).toDouble(),
                    ).toString().split(',')
                    val left = viewportPosition[0].toDouble()
                    val top = viewportPosition[1].toDouble()
                    val width = (coordinates.size.width / density).toDouble()
                    val height = (coordinates.size.height / density).toDouble()

                    if (!inputBridgeVisible) {
                        showMiuixTextInput(
                            left = left,
                            top = top,
                            width = width,
                            height = height,
                            value = value,
                            surface = colors.secondaryContainer.cssHex(),
                            text = colors.onSurface.cssHex(),
                            primary = colors.primary.cssHex(),
                            multiline = !singleLine,
                        )
                        inputBridgeVisible = true
                    } else {
                        updateMiuixTextInputBounds(left, top, width, height)
                    }
                },
        )
    }
}

internal actual fun readEditableInputValue(fallback: String): String =
    if (inputBridgeVisible) readMiuixTextInput().toString() else fallback

internal actual fun hideEditableInput() {
    if (inputBridgeVisible) removeMiuixTextInput()
    inputBridgeVisible = false
}

private fun androidx.compose.ui.graphics.Color.cssHex(): String =
    "#${toArgb().toUInt().toString(16).padStart(8, '0').takeLast(6)}"
