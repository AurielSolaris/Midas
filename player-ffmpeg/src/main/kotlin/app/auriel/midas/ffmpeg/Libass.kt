package app.auriel.midas.ffmpeg

import app.auriel.midas.core.AssRenderer
import app.auriel.midas.data.SubtitleStyle
import app.auriel.midas.subtitles.SubtitleBitmap
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.File

/** libass's C API (0.17), the parts Midas uses. */
@Suppress("FunctionName")
internal interface LibassApi : Library {
    fun ass_library_version(): Int
    fun ass_library_init(): Pointer?
    fun ass_library_done(lib: Pointer)
    fun ass_set_extract_fonts(lib: Pointer, extract: Int)
    fun ass_add_font(lib: Pointer, name: String, data: ByteArray, size: Int)
    fun ass_renderer_init(lib: Pointer): Pointer?
    fun ass_renderer_done(r: Pointer)
    fun ass_set_frame_size(r: Pointer, w: Int, h: Int)
    fun ass_set_storage_size(r: Pointer, w: Int, h: Int)
    fun ass_set_font_scale(r: Pointer, scale: Double)
    fun ass_set_fonts(r: Pointer, defaultFont: String?, defaultFamily: String?, provider: Int, config: String?, update: Int)
    fun ass_set_selective_style_override_enabled(r: Pointer, bits: Int)
    fun ass_set_selective_style_override(r: Pointer, style: AssStyle)
    fun ass_set_cache_limits(r: Pointer, glyphMax: Int, bitmapMaxSizeMb: Int)
    fun ass_render_frame(r: Pointer, track: Pointer, now: Long, changed: IntByReference?): Pointer?
    fun ass_new_track(lib: Pointer): Pointer?
    fun ass_free_track(track: Pointer)
    fun ass_process_codec_private(track: Pointer, data: ByteArray, size: Int)
    fun ass_process_chunk(track: Pointer, data: ByteArray, size: Int, timecode: Long, duration: Long)
    fun ass_read_memory(lib: Pointer, buf: ByteArray, size: Long, codepage: String?): Pointer?
}

/** ASS_Style, used to override a script's styles with the user's choices. */
@Suppress("PropertyName", "unused")
@Structure.FieldOrder(
    "Name", "FontName", "FontSize", "PrimaryColour", "SecondaryColour", "OutlineColour", "BackColour",
    "Bold", "Italic", "Underline", "StrikeOut", "ScaleX", "ScaleY", "Spacing", "Angle", "BorderStyle",
    "Outline", "Shadow", "Alignment", "MarginL", "MarginR", "MarginV", "Encoding", "treat_fontname_as_pattern",
    "Blur", "Justify",
)
internal class AssStyle : Structure() {
    @JvmField var Name: String? = "MidasOverride"
    @JvmField var FontName: String? = "Arial"
    @JvmField var FontSize: Double = 18.0
    @JvmField var PrimaryColour: Int = 0x00FFFFFF.toInt() shl 8
    @JvmField var SecondaryColour: Int = 0
    @JvmField var OutlineColour: Int = 0
    @JvmField var BackColour: Int = 0
    @JvmField var Bold: Int = 0
    @JvmField var Italic: Int = 0
    @JvmField var Underline: Int = 0
    @JvmField var StrikeOut: Int = 0
    @JvmField var ScaleX: Double = 1.0
    @JvmField var ScaleY: Double = 1.0
    @JvmField var Spacing: Double = 0.0
    @JvmField var Angle: Double = 0.0
    @JvmField var BorderStyle: Int = 1
    @JvmField var Outline: Double = 2.0
    @JvmField var Shadow: Double = 0.0
    @JvmField var Alignment: Int = 2
    @JvmField var MarginL: Int = 20
    @JvmField var MarginR: Int = 20
    @JvmField var MarginV: Int = 20
    @JvmField var Encoding: Int = 1
    @JvmField var treat_fontname_as_pattern: Int = 0
    @JvmField var Blur: Double = 0.0
    @JvmField var Justify: Int = 0
}

/**
 * Loads libass from the app's bundled natives (Windows: appResources/windows-x64/libass; elsewhere the
 * system library). Everything here is optional: without libass, ASS falls back to plain text.
 */
internal object Libass {
    private const val ASS_FONTPROVIDER_AUTODETECT = 1
    private const val ASS_FONTPROVIDER_DIRECTWRITE = 4

    val api: LibassApi? by lazy { load() }
    val available: Boolean get() = api != null

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun SetDllDirectoryW(path: WString?): Boolean
    }

    private fun load(): LibassApi? {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        return runCatching {
            if (windows) {
                val dir = nativeDir() ?: error("libass DLLs not found")
                // The dependencies (freetype, harfbuzz…) sit next to libass; let the loader find them there.
                Native.load("kernel32", Kernel32::class.java).SetDllDirectoryW(WString(dir.absolutePath))
                Native.load(File(dir, "libass-9.dll").absolutePath, LibassApi::class.java)
            } else {
                Native.load("ass", LibassApi::class.java)
            }
        }.onFailure { System.err.println("Midas: libass not loaded (${it.message}); ASS subtitles show as plain text") }
            .getOrNull()
    }

    /** Packaged app: Compose's resources folder; `gradlew run`: the same folder in the source tree. */
    private fun nativeDir(): File? {
        val candidates = listOfNotNull(
            System.getProperty("midas.libass.dir")?.let(::File),
            System.getProperty("compose.application.resources.dir")?.let { File(it, "libass") },
            File("appResources/windows-x64/libass"),
            File("midasApp-desktop/appResources/windows-x64/libass"),
            File("../midasApp-desktop/appResources/windows-x64/libass"),
        )
        return candidates.firstOrNull { File(it, "libass-9.dll").isFile }
    }

    fun fontProvider(): Int =
        if (System.getProperty("os.name").lowercase().contains("win")) ASS_FONTPROVIDER_DIRECTWRITE else ASS_FONTPROVIDER_AUTODETECT
}

/** A libass library instance: one per opened file, holding the fonts attached to it. */
internal class AssLibrary private constructor(val api: LibassApi, val handle: Pointer) {
    private val renderers = ArrayList<AssTrackRenderer>()
    @Volatile var scale: Float = 1f
        private set
    @Volatile var style: SubtitleStyle = SubtitleStyle()
        private set

    fun addFont(name: String, data: ByteArray) = synchronized(this) { api.ass_add_font(handle, name, data, data.size) }

    fun newTrack(codecPrivate: ByteArray?): AssTrackRenderer? = synchronized(this) {
        val track = api.ass_new_track(handle) ?: return null
        codecPrivate?.takeIf { it.isNotEmpty() }?.let { api.ass_process_codec_private(track, it, it.size) }
        register(track)
    }

    fun readScript(script: String): AssTrackRenderer? = synchronized(this) {
        val bytes = script.toByteArray(Charsets.UTF_8)
        val track = api.ass_read_memory(handle, bytes, bytes.size.toLong(), null) ?: return null
        register(track)
    }

    private fun register(track: Pointer): AssTrackRenderer? {
        val r = api.ass_renderer_init(handle) ?: run { api.ass_free_track(track); return null }
        api.ass_set_fonts(r, null, "sans-serif", Libass.fontProvider(), null, 1)
        api.ass_set_cache_limits(r, 0, 64)
        val renderer = AssTrackRenderer(this, r, track)
        renderer.applyStyle(scale, style)
        renderers += renderer
        return renderer
    }

    fun setStyle(scale: Float, style: SubtitleStyle) = synchronized(this) {
        this.scale = scale
        this.style = style
        renderers.forEach { it.applyStyle(scale, style) }
    }

    internal fun forget(r: AssTrackRenderer) = synchronized(this) { renderers -= r }

    fun close() = synchronized(this) {
        renderers.toList().forEach { it.close() }
        api.ass_library_done(handle)
    }

    companion object {
        fun create(): AssLibrary? {
            val api = Libass.api ?: return null
            val lib = api.ass_library_init() ?: return null
            api.ass_set_extract_fonts(lib, 1)
            return AssLibrary(api, lib)
        }
    }
}

/**
 * One ASS track and its renderer. The demux thread feeds events ([processChunk]) while the UI's render
 * thread draws frames, so both go through the same lock.
 */
internal class AssTrackRenderer(private val owner: AssLibrary, private val renderer: Pointer, private val track: Pointer) : AssRenderer {
    private val api = owner.api
    private val lock = Any()
    private var closed = false
    private var frameW = 0
    private var frameH = 0
    private var last: SubtitleBitmap? = null
    private var lastValid = false
    private val changed = IntByReference()

    fun setStorageSize(width: Int, height: Int) = synchronized(lock) {
        if (!closed && width > 0 && height > 0) api.ass_set_storage_size(renderer, width, height)
    }

    fun processChunk(data: ByteArray, startMs: Long, durationMs: Long) = synchronized(lock) {
        if (!closed) api.ass_process_chunk(track, data, data.size, startMs, durationMs)
    }

    fun applyStyle(scale: Float, style: SubtitleStyle) = synchronized(lock) {
        if (closed) return
        api.ass_set_font_scale(renderer, scale.toDouble())
        if (!style.overrideAss) {
            api.ass_set_selective_style_override_enabled(renderer, 0)
        } else {
            val boxed = (style.backgroundColor ushr 24) > 0
            val s = AssStyle().apply {
                FontName = style.fontFamily.ifBlank { "Arial" }
                // Override sizes are in units of a 288-line script; ~5 % of the height like plain subtitles.
                FontSize = 288 * 0.055
                PrimaryColour = assColor(style.textColor)
                SecondaryColour = assColor(style.textColor)
                Bold = if (style.bold) 1 else 0
                if (boxed) {
                    BorderStyle = 3 // opaque box, drawn in the outline color
                    OutlineColour = assColor(style.backgroundColor)
                    Outline = 2.0
                } else {
                    BorderStyle = 1
                    OutlineColour = assColor(style.outlineColor)
                    Outline = 1.6 * style.outlineWidth
                }
                BackColour = assColor(0x80000000)
                Shadow = 0.0
            }
            val bits = OVERRIDE_FONT_SIZE_FIELDS or OVERRIDE_COLORS or OVERRIDE_BORDER or OVERRIDE_ATTRIBUTES or
                (if (style.fontFamily.isNotBlank()) OVERRIDE_FONT_NAME else 0)
            api.ass_set_selective_style_override(renderer, s)
            api.ass_set_selective_style_override_enabled(renderer, bits)
        }
        lastValid = false
    }

    override fun render(time: Double, width: Int, height: Int): SubtitleBitmap? = synchronized(lock) {
        if (closed || width <= 0 || height <= 0) return null
        if (width != frameW || height != frameH) {
            api.ass_set_frame_size(renderer, width, height)
            frameW = width
            frameH = height
            lastValid = false
        }
        val images = api.ass_render_frame(renderer, track, (time * 1000).toLong(), changed)
        if (lastValid && changed.value == 0) return last
        last = images?.let { composite(it, width, height) }
        lastValid = true
        return last
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            api.ass_renderer_done(renderer)
            api.ass_free_track(track)
        }
        owner.forget(this)
    }

    /**
     * Blends libass's alpha masks (each with one color) into a BGRA premultiplied image, cropped to the
     * area they cover. ASS_Image: int w, h, stride; uint8* bitmap; uint32 color (RRGGBBAA, AA = transparency);
     * int dst_x, dst_y; ASS_Image* next.
     */
    private fun composite(first: Pointer, width: Int, height: Int): SubtitleBitmap? {
        class Img(val w: Int, val h: Int, val stride: Int, val bitmap: Pointer, val color: Int, val x: Int, val y: Int)
        val list = ArrayList<Img>()
        var p: Pointer? = first
        while (p != null) {
            val w = p.getInt(0)
            val h = p.getInt(4)
            if (w > 0 && h > 0) list += Img(w, h, p.getInt(8), p.getPointer(16), p.getInt(24), p.getInt(28), p.getInt(32))
            p = p.getPointer(40)
        }
        if (list.isEmpty()) return null
        val left = list.minOf { it.x }.coerceAtLeast(0)
        val top = list.minOf { it.y }.coerceAtLeast(0)
        val right = list.maxOf { it.x + it.w }.coerceAtMost(width)
        val bottom = list.maxOf { it.y + it.h }.coerceAtMost(height)
        val bw = right - left
        val bh = bottom - top
        if (bw <= 0 || bh <= 0) return null
        val out = ByteArray(bw * bh * 4)
        for (img in list) {
            val r = img.color ushr 24 and 0xFF
            val g = img.color ushr 16 and 0xFF
            val b = img.color ushr 8 and 0xFF
            val opacity = 255 - (img.color and 0xFF)
            if (opacity == 0) continue
            val row = ByteArray(img.w)
            for (yy in 0 until img.h) {
                val y = img.y + yy - top
                if (y < 0 || y >= bh) continue
                img.bitmap.read((yy * img.stride).toLong(), row, 0, img.w)
                for (xx in 0 until img.w) {
                    val k = row[xx].toInt() and 0xFF
                    if (k == 0) continue
                    val x = img.x + xx - left
                    if (x < 0 || x >= bw) continue
                    val a = k * opacity / 255
                    val i = (y * bw + x) * 4
                    val inv = 255 - a
                    out[i] = ((b * a + (out[i].toInt() and 0xFF) * inv) / 255).toByte()
                    out[i + 1] = ((g * a + (out[i + 1].toInt() and 0xFF) * inv) / 255).toByte()
                    out[i + 2] = ((r * a + (out[i + 2].toInt() and 0xFF) * inv) / 255).toByte()
                    out[i + 3] = (a + (out[i + 3].toInt() and 0xFF) * inv / 255).toByte()
                }
            }
        }
        return SubtitleBitmap(left, top, bw, bh, out, width, height)
    }

    private companion object {
        const val OVERRIDE_FONT_SIZE_FIELDS = 1 shl 2
        const val OVERRIDE_FONT_NAME = 1 shl 3
        const val OVERRIDE_COLORS = 1 shl 4
        const val OVERRIDE_ATTRIBUTES = 1 shl 5
        const val OVERRIDE_BORDER = 1 shl 6

        /** 0xAARRGGBB → libass's 0xRRGGBBTT (TT = transparency). */
        fun assColor(argb: Long): Int {
            val a = (argb ushr 24 and 0xFF).toInt()
            val rgb = (argb and 0xFFFFFF).toInt()
            return (rgb shl 8) or (255 - a)
        }
    }
}
