package app.auriel.midas.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Baseline tonal schemes (Material Theme Builder roles). These are the only raw colors in the app;
// everything else reads MaterialTheme.colorScheme.

private val LightColors = lightColorScheme(
    primary = Color(0xFF415F91), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF284777),
    secondary = Color(0xFF565F71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF705575), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD), onTertiaryContainer = Color(0xFF573E5C),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2E3036), inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFAAC7FF),
    surfaceDim = Color(0xFFD9D9E0), surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC), onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0), onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C), onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
    inverseSurface = Color(0xFFE2E2E9), inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF415F91),
    surfaceDim = Color(0xFF111318), surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** Halloween: pumpkin-orange primary and witch-purple secondary on night-purple surfaces. */
private val HalloweenColors = darkColorScheme(
    primary = Color(0xFFFFA24C), onPrimary = Color(0xFF3D1C00),
    primaryContainer = Color(0xFF7A3E00), onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFD6BAFF), onSecondary = Color(0xFF3B1F66),
    secondaryContainer = Color(0xFF52367E), onSecondaryContainer = Color(0xFFEDDCFF),
    tertiary = Color(0xFFE5B8FF), onTertiary = Color(0xFF45185F),
    tertiaryContainer = Color(0xFF5E3178), onTertiaryContainer = Color(0xFFF5D9FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14101A), onBackground = Color(0xFFE9E0EC),
    surface = Color(0xFF14101A), onSurface = Color(0xFFE9E0EC),
    surfaceVariant = Color(0xFF4A4352), onSurfaceVariant = Color(0xFFCDC3D6),
    outline = Color(0xFF968DA0), outlineVariant = Color(0xFF4A4352),
    inverseSurface = Color(0xFFE9E0EC), inverseOnSurface = Color(0xFF322C38),
    inversePrimary = Color(0xFF8A4A00),
    surfaceDim = Color(0xFF14101A), surfaceBright = Color(0xFF3B3542),
    surfaceContainerLowest = Color(0xFF0F0B14), surfaceContainerLow = Color(0xFF1C1723),
    surfaceContainer = Color(0xFF211B28), surfaceContainerHigh = Color(0xFF2B2533),
    surfaceContainerHighest = Color(0xFF36303E),
)

/** Gold: burnished-gold primary and emerald secondary on deep forest-green surfaces. */
private val GoldColors = darkColorScheme(
    primary = Color(0xFFE9C349), onPrimary = Color(0xFF3C2F00),
    primaryContainer = Color(0xFF574500), onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF7FD8A4), onSecondary = Color(0xFF00391F),
    secondaryContainer = Color(0xFF0B5132), onSecondaryContainer = Color(0xFF9BF5BF),
    tertiary = Color(0xFFC9D27A), onTertiary = Color(0xFF2F3400),
    tertiaryContainer = Color(0xFF454C00), onTertiaryContainer = Color(0xFFE5EE93),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1712), onBackground = Color(0xFFDFE8DE),
    surface = Color(0xFF0E1712), onSurface = Color(0xFFDFE8DE),
    surfaceVariant = Color(0xFF3F4A41), onSurfaceVariant = Color(0xFFC0CABF),
    outline = Color(0xFF8A9489), outlineVariant = Color(0xFF3F4A41),
    inverseSurface = Color(0xFFDFE8DE), inverseOnSurface = Color(0xFF2B352E),
    inversePrimary = Color(0xFF735C00),
    surfaceDim = Color(0xFF0E1712), surfaceBright = Color(0xFF333E37),
    surfaceContainerLowest = Color(0xFF09110C), surfaceContainerLow = Color(0xFF16201A),
    surfaceContainer = Color(0xFF1A251E), surfaceContainerHigh = Color(0xFF243029),
    surfaceContainerHighest = Color(0xFF2F3B33),
)

enum class AppTheme(val label: String, internal val colors: ColorScheme, val isDark: Boolean) {
    Light("Light", LightColors, false),
    Dark("Dark", DarkColors, true),
    Halloween("Halloween", HalloweenColors, true),
    Gold("Gold", GoldColors, true);

    /** Two swatches for the theme picker. */
    val swatches: List<Color> get() = listOf(colors.primary, colors.secondary, colors.surface)

    companion object {
        fun fromName(name: String?): AppTheme = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Dark
    }
}

/** The active theme, for themed decorations (cobwebs, gold sparkles). */
val LocalAppTheme = staticCompositionLocalOf { AppTheme.Dark }

/** MD3 corner tokens: extra-small 4, small 8, medium 12, large 16, extra-large 28. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 8dp spacing scale (4dp half-step for tight groupings). */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

@Composable
fun MidasTheme(theme: AppTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = theme.colors, shapes = AppShapes, content = content)
    }
}

/**
 * Color roles for the player: controls sit on a scrim over video, so Light switches to Dark there.
 * Halloween and Gold are already dark and keep their own accents.
 */
@Composable
fun MediaTheme(content: @Composable () -> Unit) {
    val theme = LocalAppTheme.current
    MaterialTheme(
        colorScheme = if (theme.isDark) theme.colors else DarkColors,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
