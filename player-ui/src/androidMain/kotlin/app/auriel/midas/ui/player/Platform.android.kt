package app.auriel.midas.ui.player

import android.provider.OpenableColumns
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
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
    DisposableEffect(androidEngine) { onDispose { androidEngine.captureFrame = null } }
    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context).inflate(app.auriel.midas.ui.R.layout.midas_player_view, null) as PlayerView).apply {
                useController = false
                player = androidEngine.player
                androidEngine.captureFrame = {
                    val bitmap = (videoSurfaceView as? TextureView)?.bitmap
                    bitmap?.let {
                        val colors = IntArray(it.width * it.height)
                        it.getPixels(colors, 0, it.width, 0, 0, it.width, it.height)
                        val pixels = ByteArray(colors.size * 4)
                        colors.forEachIndexed { i, color ->
                            pixels[i * 4] = color.toByte()
                            pixels[i * 4 + 1] = (color shr 8).toByte()
                            pixels[i * 4 + 2] = (color shr 16).toByte()
                            pixels[i * 4 + 3] = 0xff.toByte()
                        }
                        val frame = VideoFrame(it.width, it.height, it.width * 4, pixels, androidEngine.clock.now())
                        it.recycle()
                        frame
                    }
                }
            }
        },
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

@Composable
actual fun rememberScreenshotSaver(): (VideoFrame, String) -> String {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { frame, baseName ->
            val name = "${baseName.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(80)} ${System.currentTimeMillis()}.png"
            val colors = IntArray(frame.width * frame.height) { i ->
                val p = (i / frame.width) * frame.rowBytes + (i % frame.width) * 4
                (0xff shl 24) or ((frame.pixels[p + 2].toInt() and 255) shl 16) or
                    ((frame.pixels[p + 1].toInt() and 255) shl 8) or (frame.pixels[p].toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(colors, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Midas")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create screenshot")
                    try {
                        resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("Could not open screenshot")
                        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                } else {
                    val dir = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Midas").apply { mkdirs() }
                    java.io.File(dir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
                name
            } finally { bitmap.recycle() }
        }
    }
}
