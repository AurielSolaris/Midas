package app.auriel.midas.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the window is narrower than 600dp (MD3's "compact" width class): phones in portrait.
 * Compact layouts swap the navigation rail for a bottom bar and the sidebar for a drawer.
 */
val LocalCompact = staticCompositionLocalOf { false }

/**
 * Fills the whole window with the current screen: hides the navigation rail/bar and, through the
 * platform, makes the window fullscreen (desktop) or hides the system bars (Android).
 */
class FullscreenController(private val onChange: (Boolean) -> Unit) {
    var isFullscreen by mutableStateOf(false)
        private set

    fun set(fullscreen: Boolean) {
        if (fullscreen == isFullscreen) return
        isFullscreen = fullscreen
        onChange(fullscreen)
    }

    fun toggle() = set(!isFullscreen)
}

val LocalFullscreen = staticCompositionLocalOf { FullscreenController {} }
