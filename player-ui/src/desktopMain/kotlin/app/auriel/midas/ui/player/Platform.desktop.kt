package app.auriel.midas.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.auriel.midas.core.AssRenderer
import app.auriel.midas.core.PlaybackEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import app.auriel.midas.core.VideoFrame
import app.auriel.midas.core.VideoFrameSource
import app.auriel.midas.subtitles.SubtitleBitmap
import com.sun.jna.Library
import com.sun.jna.Native
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One Skia image per new frame; frames are BGRA, which is Skia's native order on desktop. */
private class FrameImage(val frame: VideoFrame, val image: Image)

@Composable
actual fun VideoSurface(
    engine: PlaybackEngine,
    fill: Boolean,
    overlays: List<SubtitleBitmap>,
    ass: AssRenderer?,
    assTime: () -> Double,
    modifier: Modifier,
) {
    val source = engine as? VideoFrameSource
    var shown by remember(engine) { mutableStateOf<FrameImage?>(null) }
    LaunchedEffect(source) {
        if (source == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val frame = source.currentFrame() ?: continue
            if (frame === shown?.frame) continue
            val info = ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            // makeRaster copies the pixels, so the engine can reuse the buffer afterwards.
            val image = Image.makeRaster(info, frame.pixels, frame.rowBytes)
            val old = shown
            shown = FrameImage(frame, image)
            old?.image?.close()
        }
    }
    DisposableEffect(engine) { onDispose { shown?.image?.close() } }

    // Skia images for the picture subtitles on screen; dropped when they go away.
    val overlayImages = remember { HashMap<SubtitleBitmap, Image>() }
    val images = overlays.map { b ->
        b to overlayImages.getOrPut(b) {
            Image.makeRaster(ImageInfo(b.width, b.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), b.pixels, b.width * 4)
        }
    }
    // Full-styling ASS: libass renders on its own thread at the picture's on-screen size; the newest
    // result is picked up here once per frame.
    val pictureSize = remember { IntArray(2) }
    var assShown by remember { mutableStateOf<Pair<SubtitleBitmap, Image>?>(null) }
    val assTimeNow = rememberUpdatedState(assTime)
    LaunchedEffect(ass) {
        assShown?.second?.close()
        assShown = null
        if (ass == null) return@LaunchedEffect
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "midas-ass").apply { isDaemon = true } }
        val busy = AtomicBoolean(false)
        val result = AtomicReference<Array<SubtitleBitmap?>?>(null)
        try {
            while (true) {
                withFrameNanos { }
                result.getAndSet(null)?.let { r ->
                    val b = r[0]
                    if (b !== assShown?.first) {
                        val old = assShown
                        assShown = b?.let {
                            it to Image.makeRaster(ImageInfo(it.width, it.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), it.pixels, it.width * 4)
                        }
                        old?.second?.close()
                    }
                }
                val w = pictureSize[0]
                val h = pictureSize[1]
                if (w > 0 && h > 0 && busy.compareAndSet(false, true)) {
                    val t = assTimeNow.value()
                    worker.execute {
                        val b = runCatching { ass.render(t, w, h) }.getOrNull()
                        result.set(arrayOf(b))
                        busy.set(false)
                    }
                }
            }
        } finally {
            worker.shutdownNow()
        }
    }

    val live = overlays.toSet()
    overlayImages.keys.filter { it !in live }.forEach { overlayImages.remove(it)?.close() }
    DisposableEffect(Unit) { onDispose { overlayImages.values.forEach { it.close() }; overlayImages.clear() } }

    Canvas(modifier) {
        val current = shown
        val aspect = current?.frame?.displayAspect?.toFloat()
            ?: images.firstOrNull()?.first?.let { it.canvasWidth.toFloat() / it.canvasHeight }
            ?: (16f / 9f).takeIf { ass != null } ?: return@Canvas
        val boxAspect = size.width / size.height
        val (w, h) = if ((aspect > boxAspect) != fill) size.width to size.width / aspect else size.height * aspect to size.height
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawIntoCanvas { canvas ->
            if (current != null) {
                canvas.nativeCanvas.drawImageRect(
                    current.image,
                    Rect.makeWH(current.frame.width.toFloat(), current.frame.height.toFloat()),
                    Rect.makeXYWH(left, top, w, h),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                    null,
                    true,
                )
            }
            // libass draws at the picture's on-screen pixel size, so its canvas maps 1:1 (scaled while resizing).
            pictureSize[0] = w.toInt()
            pictureSize[1] = h.toInt()
            assShown?.let { (b, image) ->
                val sx = w / b.canvasWidth
                val sy = h / b.canvasHeight
                canvas.nativeCanvas.drawImageRect(
                    image,
                    Rect.makeWH(b.width.toFloat(), b.height.toFloat()),
                    Rect.makeXYWH(left + b.x * sx, top + b.y * sy, b.width * sx, b.height * sy),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                    null,
                    true,
                )
            }
            // Picture subtitles: their canvas maps onto the picture rectangle.
            for ((b, image) in images) {
                val sx = w / b.canvasWidth
                val sy = h / b.canvasHeight
                canvas.nativeCanvas.drawImageRect(
                    image,
                    Rect.makeWH(b.width.toFloat(), b.height.toFloat()),
                    Rect.makeXYWH(left + b.x * sx, top + b.y * sy, b.width * sx, b.height * sy),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                    null,
                    true,
                )
            }
        }
    }
}

// ---- Fonts ----

actual fun systemFontFamilies(): List<String> =
    runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList() }
        .getOrDefault(emptyList())
        .filterNot { it.startsWith(".") || it in setOf("Dialog", "DialogInput", "Monospaced", "SansSerif", "Serif") }
        .distinct()
        .sortedBy { it.lowercase() }

private val fontFamilies = HashMap<String, FontFamily>()

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
actual fun fontFamilyOf(name: String): FontFamily {
    if (name.isBlank()) return FontFamily.SansSerif
    return synchronized(fontFamilies) {
        fontFamilies.getOrPut(name) {
            runCatching {
                FontFamily(
                    androidx.compose.ui.text.platform.SystemFont(name, FontWeight.Normal),
                    androidx.compose.ui.text.platform.SystemFont(name, FontWeight.SemiBold),
                    androidx.compose.ui.text.platform.SystemFont(name, FontWeight.Bold),
                )
            }.getOrDefault(FontFamily.SansSerif)
        }
    }
}

// ---- Keep awake ----

@Suppress("FunctionName")
private interface Kernel32 : Library {
    fun SetThreadExecutionState(flags: Int): Int
}

private object Awake {
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002
    private val os = System.getProperty("os.name").lowercase()
    private val kernel32: Kernel32? by lazy {
        if ("win" in os) runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() else null
    }
    private var thread: Thread? = null
    private var inhibitor: Process? = null

    @Synchronized
    fun set(on: Boolean) {
        if (on == (thread != null || inhibitor != null)) return
        if (!on) {
            thread?.interrupt()
            thread = null
            inhibitor?.destroy()
            inhibitor = null
            return
        }
        when {
            "win" in os -> thread = Thread({
                // Resetting the idle timers every 30 s keeps the display on without a continuous state to undo.
                try {
                    while (true) {
                        kernel32?.SetThreadExecutionState(ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
                        Thread.sleep(30_000)
                    }
                } catch (_: InterruptedException) {
                }
            }, "midas-keep-awake").apply { isDaemon = true; start() }
            "mac" in os -> inhibitor = runCatching {
                ProcessBuilder("caffeinate", "-d", "-w", ProcessHandle.current().pid().toString()).start()
            }.getOrNull()
            else -> inhibitor = runCatching {
                ProcessBuilder("systemd-inhibit", "--what=idle", "--who=Midas", "--why=Playing video", "sleep", "infinity").start()
            }.getOrNull()
        }
    }
}

@Composable
actual fun KeepScreenOn(enabled: Boolean) {
    DisposableEffect(enabled) {
        Awake.set(enabled)
        onDispose { Awake.set(false) }
    }
}

// ---- Files ----

private val SUBTITLE_PICKER_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "zip")

@Composable
actual fun rememberSubtitleFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "Load subtitles", FileDialog.LOAD).apply {
                file = "*.srt;*.ass;*.ssa;*.vtt;*.zip"
                setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in SUBTITLE_PICKER_EXTENSIONS }
                isVisible = true // blocks until closed
            }
            val dir = dialog.directory
            val name = dialog.file
            if (dir != null && name != null) {
                val f = File(dir, name)
                callback.value(f.name, f.readBytes())
            }
        }
    }
}

private val UNSAFE_FILE_CHARS = Regex("""[\\/:*?"<>|]""")

@Composable
actual fun rememberScreenshotSaver(): (VideoFrame, String) -> String = { frame, baseName -> saveScreenshot(frame, baseName) }

private fun saveScreenshot(frame: VideoFrame, baseName: String): String {
    val home = File(System.getProperty("user.home"))
    val pictures = listOf(File(home, "Pictures"), File(home, "OneDrive/Pictures")).firstOrNull { it.isDirectory } ?: home
    val dir = File(pictures, "Midas").apply { mkdirs() }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))
    val file = File(dir, "${baseName.replace(UNSAFE_FILE_CHARS, "_").take(80)} $stamp.png")
    val info = ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    val image = Image.makeRaster(info, frame.pixels, frame.rowBytes)
    try {
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
        file.writeBytes(data.bytes)
    } finally {
        image.close()
    }
    return file.path
}
