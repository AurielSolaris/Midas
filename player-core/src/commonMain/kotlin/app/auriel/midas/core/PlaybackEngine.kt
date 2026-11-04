package app.auriel.midas.core

import app.auriel.midas.data.SubtitleStyle
import app.auriel.midas.subtitles.SubtitleBitmap
import app.auriel.midas.subtitles.SubtitleTrack
import kotlinx.coroutines.flow.StateFlow

/**
 * A video player backend. The desktop one is FFmpeg ([player-ffmpeg]); Android will use Media3.
 * The UI only talks to this interface.
 */
interface PlaybackEngine : AutoCloseable {
    val state: StateFlow<PlaybackState>
    val isPlaying: StateFlow<Boolean>

    /** Position in seconds. Smooth: the UI reads it every frame. */
    val clock: PlaybackClock
    val duration: StateFlow<Double?>
    val tracks: StateFlow<MediaTracks>

    /** Selected track ids (null = off / none). */
    val selectedAudio: StateFlow<String?>
    val selectedSubtitle: StateFlow<String?>

    /**
     * Cues of the embedded text subtitle tracks, keyed by track id. They fill in as the file is read,
     * which is always ahead of playback.
     */
    val embeddedSubtitles: StateFlow<Map<String, SubtitleTrack>>

    fun open(source: MediaSource, startAt: Double = 0.0)
    fun play()
    fun pause()

    /** Keyframe seek while dragging ([exact] = false), exact on release. */
    fun seek(seconds: Double, exact: Boolean = false)
    fun selectAudio(id: String?)
    fun selectSubtitle(id: String?)

    /** 0..1 */
    var volume: Float
    var speed: Float

    /** Seconds; positive delays the sound. */
    var audioDelay: Double

    /** One frame forward or back. Pauses playback. */
    fun frameStep(forward: Boolean)

    /** The frame on screen now, as BGRA pixels. */
    fun screenshot(): VideoFrame?
}

/** Renders ASS/SSA subtitles with all their styling (fonts, colors, positions, karaoke, drawings). */
interface AssRenderer : AutoCloseable {
    /**
     * The subtitles showing at [time] seconds for a picture drawn [width]×[height] pixels on screen,
     * as one image whose canvas is that picture; null when nothing shows.
     */
    fun render(time: Double, width: Int, height: Int): SubtitleBitmap?
}

/** Engines that can draw ASS/SSA with full styling (libass on desktop). */
interface AssSupport {
    /** False when the renderer couldn't be loaded; ASS then shows as plain text. */
    val assAvailable: Boolean

    /** For one of the file's own ASS tracks (fonts attached to the file included); null if it isn't one. */
    fun embeddedAssRenderer(trackId: String): AssRenderer?

    /** For a loaded .ass/.ssa script. */
    fun assScriptRenderer(script: String): AssRenderer?

    /** The user's size, and their style when it should replace the script's. Applies to all renderers. */
    fun setAssStyle(scale: Float, style: SubtitleStyle)
}

/** Engines that hand decoded frames to the UI (desktop). Android engines render to a surface instead. */
interface VideoFrameSource {
    /** The frame to show at the current clock time, or null before the first frame. Called every UI frame. */
    fun currentFrame(): VideoFrame?
}

fun interface PlaybackClock {
    fun now(): Double
}

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data object Ready : PlaybackState
    data object Ended : PlaybackState
    data class Error(val message: String) : PlaybackState
}

sealed interface MediaSource {
    /** A short name for titles and resume keys. */
    val displayName: String

    data class File(val path: String) : MediaSource {
        override val displayName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    }

    /** Android SAF / MediaStore. */
    data class ContentUri(val uri: String) : MediaSource {
        override val displayName: String get() = uri.substringAfterLast('/')
    }

    data class Http(val url: String, val headers: Map<String, String> = emptyMap()) : MediaSource {
        override val displayName: String get() = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
    }
}

/**
 * One decoded frame. [pixels] are BGRA (premultiplied, opaque), [rowBytes] per row.
 * Frames are immutable once handed out.
 */
class VideoFrame(
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val pixels: ByteArray,
    /** Presentation time in seconds. */
    val pts: Double,
    /** Sample aspect ratio (non-square pixels in some DVD/broadcast files). */
    val pixelAspect: Double = 1.0,
) {
    val displayAspect: Double get() = width * pixelAspect / height
}

data class MediaTracks(
    val video: List<TrackInfo> = emptyList(),
    val audio: List<TrackInfo> = emptyList(),
    val subtitle: List<TrackInfo> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)

data class TrackInfo(
    val id: String,
    val codec: String,
    /** ISO 639 code as stored in the file (often three letters, e.g. "jpn"). */
    val language: String?,
    val title: String?,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** Subtitles only: false for bitmap formats (PGS, VobSub), which can't be shown yet. */
    val isText: Boolean = true,
    /** Video: "1920×1080", audio: "5.1 · 48 kHz". */
    val detail: String? = null,
) {
    /** "English · Signs & Songs · ASS", for menus. */
    val label: String
        get() = listOfNotNull(language?.let(Languages::name), title?.takeIf { it.isNotBlank() }, codec.uppercase(), detail)
            .distinct()
            .joinToString(" · ")
            .ifBlank { "Track $id" }
}

data class Chapter(val start: Double, val end: Double, val title: String?) {
    /** Opening or ending, which get a "Skip" button. */
    val skipKind: SkipKind?
        get() {
            val t = title?.lowercase()?.trim() ?: return null
            return when {
                OPENING.containsMatchIn(t) -> SkipKind.Intro
                ENDING.containsMatchIn(t) -> SkipKind.Ending
                else -> null
            }
        }

    enum class SkipKind(val label: String) { Intro("Skip intro"), Ending("Skip ending") }

    private companion object {
        val OPENING = Regex("""^(op|opening|intro|opening song|opening credits)\b|\bopening\b|^op\d""")
        val ENDING = Regex("""^(ed|ending|outro|credits|ending song|end credits)\b|\bending\b|^ed\d""")
    }
}
