package app.auriel.midas.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double = value.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    @Test
    fun textAndButtonRolesMeetMinimumContrast() {
        AppTheme.entries.forEach { theme ->
            val c = theme.colors
            val pairs = listOf(
                "text" to (c.onSurface to c.surface),
                "text button" to (c.primary to c.surface),
                "secondary text" to (c.onSurfaceVariant to c.surface),
                "filled button" to (c.onPrimary to c.primary),
                "tonal button" to (c.onSecondaryContainer to c.secondaryContainer),
                "error" to (c.error to c.surface),
            )
            pairs.forEach { (name, colors) ->
                val values = listOf(luminance(colors.first), luminance(colors.second)).sorted()
                val ratio = (values[1] + 0.05) / (values[0] + 0.05)
                assertTrue(ratio >= 4.5, "${theme.name} $name contrast is $ratio")
            }
        }
    }
}
