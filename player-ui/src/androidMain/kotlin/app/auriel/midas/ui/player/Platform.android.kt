package app.auriel.midas.ui.player

import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.auriel.midas.android.player.AndroidPlaybackEngine
import app.auriel.midas.core.AssRenderer
import app.auriel.midas.core.PlaybackEngine
import app.auriel.midas.core.VideoFrame
import app.auriel.midas.subtitles.SubtitleBitmap

@Composable
actual fun VideoSurface(engine: PlaybackEngine, fill: Boolean, overlays: List<SubtitleBitmap>, ass: AssRenderer?, assTime: () -> Double, modifier: Modifier) {
    val androidEngine = engine as? AndroidPlaybackEngine ?: return
    AndroidView(
        factory = { context -> PlayerView(context).apply { useController = false; player = androidEngine.player } },
        update = { view ->
            view.player = androidEngine.player
            view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        modifier = modifier,
    )
}

actual fun systemFontFamilies(): List<String> = emptyList()
actual fun fontFamilyOf(name: String): FontFamily = FontFamily.SansSerif

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun rememberSubtitleFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "subtitles.srt"
            context.contentResolver.openInputStream(uri)?.use { callback(name, it.readBytes()) }
        }
    }
    return { launcher.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream", "application/zip")) }
}

actual fun saveScreenshot(frame: VideoFrame, baseName: String): String = "Screenshots are not available yet on Android"
