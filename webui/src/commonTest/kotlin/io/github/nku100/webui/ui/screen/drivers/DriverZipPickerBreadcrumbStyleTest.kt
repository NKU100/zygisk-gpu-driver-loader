package io.github.nku100.webui.ui.screen.drivers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals

class DriverZipPickerBreadcrumbStyleTest {
    @Test
    fun pathHoverAndPressShareTheFileRowHighlight() {
        val onSurface = Color(0xFF222222)
        val expectedHighlight = onSurface.copy(alpha = 0.08f)

        val hovered = driverPathInteractionHighlight(
            isHovered = true,
            isPressed = false,
            onSurface = onSurface,
        )
        val pressed = driverPathInteractionHighlight(
            isHovered = false,
            isPressed = true,
            onSurface = onSurface,
        )
        val idle = driverPathInteractionHighlight(
            isHovered = false,
            isPressed = false,
            onSurface = onSurface,
        )

        assertEquals(expectedHighlight, hovered)
        assertEquals(expectedHighlight, pressed)
        assertEquals(Color.Transparent, idle)
    }

    @Test
    fun ancestorBreadcrumbKeepsNeutralTextWithoutUnderline() {
        val style = driverPickerBreadcrumbTextStyle(
            isCurrent = false,
            onSurface = Color.Black,
            onSurfaceVariant = Color.Gray,
        )

        assertEquals(Color.Gray, style.color)
        assertEquals(TextDecoration.None, style.textDecoration)
    }

    @Test
    fun idleBreadcrumbKeepsCurrentAndAncestorTextStyles() {
        val colors = Pair(Color.Black, Color.Gray)

        val current = driverPickerBreadcrumbTextStyle(
            isCurrent = true,
            onSurface = colors.first,
            onSurfaceVariant = colors.second,
        )
        val ancestor = driverPickerBreadcrumbTextStyle(
            isCurrent = false,
            onSurface = colors.first,
            onSurfaceVariant = colors.second,
        )

        assertEquals(colors.first, current.color)
        assertEquals(colors.second, ancestor.color)
        assertEquals(TextDecoration.None, current.textDecoration)
        assertEquals(TextDecoration.None, ancestor.textDecoration)
    }
}
