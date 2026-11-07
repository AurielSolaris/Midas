package app.auriel.midas.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import app.auriel.midas.core.AssRenderer
import app.auriel.midas.core.PlaybackEngine
import app.auriel.midas.core.VideoFrame
import app.auriel.midas.subtitles.SubtitleBitmap

/**
 * Draws the engine's video, letterboxed to its display aspect ratio ([fill] crops to fill instead),
 * with picture subtitles ([overlays]) placed on the picture itself.
 */
@Composable
expect fun VideoSurface(
    engine: PlaybackEngine,
    fill: Boolean,
    overlays: List<SubtitleBitmap>,
    ass: AssRenderer? = null,
    assTime: () -> Double = { 0.0 },
    modifier: Modifier = Modifier,
)

/** Font families installed on this system, sorted. */
expect fun systemFontFamilies(): List<String>

/** A Compose font family for a system font name; blank or unknown = the default sans-serif. */
expect fun fontFamilyOf(name: String): FontFamily

/** Keeps the display awake while [enabled] (video playing). */
@Composable
expect fun KeepScreenOn(enabled: Boolean)

/** Returns a function that opens the system file picker for a subtitle file and hands back its name and bytes. */
@Composable
expect fun rememberSubtitleFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit

/** Saves [frame] as a PNG in the user's Pictures/Midas folder; returns the saved file's path. */
@Composable
expect fun rememberScreenshotSaver(): (VideoFrame, String) -> String
