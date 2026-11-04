package app.auriel.midas.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MidasSettings(
    /** One of the app's theme names: Light, Dark, Halloween, Gold. */
    val theme: String = "Dark",

    // ---- Playback ----
    val playerVolume: Float = 1f,
    /** Video decoding for playback: GPU (D3D11VA / VideoToolbox / VAAPI, software when the GPU can't) or CPU only. */
    val videoDecoding: CodecMode = CodecMode.Hardware,
    /** Video encoding for exported clips: GPU (NVENC / Quick Sync / Media Foundation / VideoToolbox) or CPU (OpenH264). */
    val videoEncoding: CodecMode = CodecMode.Hardware,
    /** At the end of a file in a folder, play the next one after a short countdown. */
    val autoplayNext: Boolean = true,
    /** Preferred audio language ("ja"); blank = the file's default track. */
    val audioLanguage: String = "",

    // ---- Subtitles ----
    /** Preferred subtitle language (ISO 639-1, e.g. "en"). */
    val subtitleLanguage: String = "en",
    /**
     * Turn on a subtitle track in [subtitleLanguage] when the audio is in another language
     * (e.g. Japanese audio → English subtitles).
     */
    val autoSubtitles: Boolean = true,
    /** Load matching .srt/.ass files from the video's folder (and Subs/ folders) automatically. */
    val autoLoadSubtitleFiles: Boolean = true,
    /** Subtitle font size, as a multiple of the default (about 5 % of the picture height). Also scales ASS. */
    val subtitleScale: Float = 1f,
    /** Font face, colors and background box for subtitles. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Free key from jimaku.cc (account page). Jimaku has most anime subtitles, Japanese and English. */
    val jimakuApiKey: String = "",
    /** Free consumer key from opensubtitles.com/consumers. */
    val openSubtitlesApiKey: String = "",
    /** Free key from subdl.com (panel → API). */
    val subDlApiKey: String = "",
    /** Video folder → folder of subtitle files matched to its episodes by number. */
    val subtitleFolders: Map<String, String> = emptyMap(),

    // ---- Library ----
    /** Media key (file path or URL) → seconds, so long videos resume where they were left. */
    val resumePositions: Map<String, Double> = emptyMap(),
    /** Most recent first. */
    val recent: List<RecentItem> = emptyList(),
    val savedFolders: List<String> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
) {
    companion object {
        const val MAX_RESUME_ENTRIES = 500
        const val MAX_RECENT = 60
    }
}

@Serializable
data class SavedPlaylist(val id: String, val name: String, val files: List<String> = emptyList())

/**
 * How plain subtitles look (SRT, VTT, mov_text, YouTube…). With [overrideAss] the same choices also
 * replace the styles of ASS/SSA subtitles; otherwise those keep their own fonts and colors.
 * Colors are 0xAARRGGBB.
 */
@Serializable
data class SubtitleStyle(
    /** Font family name; blank = the default sans-serif. */
    val fontFamily: String = "",
    val bold: Boolean = true,
    val textColor: Long = 0xFFFFFFFF,
    val outlineColor: Long = 0xFF000000,
    /** Outline thickness, 0 (none) to 3; 1 is the default. */
    val outlineWidth: Float = 1f,
    /** Box behind the text; fully transparent = no box. */
    val backgroundColor: Long = 0x00000000,
    val overrideAss: Boolean = false,
)

/** Where video is decoded or encoded. Hardware falls back to software when the GPU can't handle a stream. */
@Serializable
enum class CodecMode(val label: String) {
    Hardware("Hardware"),
    Software("Software"),
}

@Serializable
data class RecentItem(
    /** File path or URL. Also the resume key. */
    val location: String,
    val title: String,
    val openedAt: Long,
    val duration: Double? = null,
    /** Whether [location] is a folder opened as a playlist (the item is then the file inside it). */
    val folder: String? = null,
)

/** Raw local storage for the settings blob. Desktop writes a JSON file in the config folder. */
interface SettingsStorage {
    fun read(): String?
    fun write(contents: String)
}

val MidasJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    prettyPrint = true
}

class SettingsRepository(private val storage: SettingsStorage) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<MidasSettings> = _settings.asStateFlow()

    val current: MidasSettings get() = _settings.value

    fun update(transform: (MidasSettings) -> MidasSettings) {
        _settings.update(transform)
        storage.write(MidasJson.encodeToString(MidasSettings.serializer(), _settings.value))
    }

    /** Saves (or clears, when [seconds] is null) the resume position for [key]. */
    fun saveResume(key: String, seconds: Double?) = update { s ->
        val rest = s.resumePositions - key
        val positions = if (seconds != null) rest + (key to seconds) else rest
        s.copy(resumePositions = positions.entries.toList().takeLast(MidasSettings.MAX_RESUME_ENTRIES).associate { it.key to it.value })
    }

    fun addRecent(item: RecentItem) = update { s ->
        s.copy(recent = (listOf(item) + s.recent.filter { it.location != item.location }).take(MidasSettings.MAX_RECENT))
    }

    private fun load(): MidasSettings = storage.read()
        ?.let { runCatching { MidasJson.decodeFromString(MidasSettings.serializer(), it) }.getOrNull() }
        ?: MidasSettings()
}
