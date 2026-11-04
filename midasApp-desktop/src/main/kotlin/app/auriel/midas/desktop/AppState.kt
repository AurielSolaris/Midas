package app.auriel.midas.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.auriel.midas.core.MediaSource
import app.auriel.midas.data.RecentItem
import app.auriel.midas.data.SettingsRepository
import app.auriel.midas.library.NaturalOrder
import app.auriel.midas.library.SubtitleMatcher
import app.auriel.midas.library.isSubtitleName
import app.auriel.midas.library.isVideoName
import app.auriel.midas.platform.decodeSubtitleText
import app.auriel.midas.subtitles.MediaGuesser
import app.auriel.midas.subtitles.SubtitleParser
import app.auriel.midas.subtitles.SubtitleTrack
import app.auriel.midas.ui.player.PlayerItem
import java.io.File

enum class Screen { Library, Settings, Folder, Player }

/** What's open: the screen, and the playlist (a folder's videos in natural order, or a single URL). */
class AppState(val settings: SettingsRepository) {
    var screen by mutableStateOf(Screen.Library)
    var playlist by mutableStateOf<List<MediaSource>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var folderName by mutableStateOf("")
        private set
    var folderPath by mutableStateOf<String?>(null)
        private set
    var savedPlaylistId by mutableStateOf<String?>(null)
        private set
    var folderPlaylist by mutableStateOf<List<MediaSource>>(emptyList())
        private set
    var returnToFolder by mutableStateOf(false)
        private set

    val current: MediaSource? get() = playlist.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < playlist.size
    val hasPrevious: Boolean get() = index > 0

    /** Bumped when the current item must be rebuilt (e.g. a subtitle folder was chosen). */
    var reloadTick by mutableIntStateOf(0)

    fun next() { if (hasNext) index++ }
    fun previous() { if (hasPrevious) index-- }

    fun play(list: List<MediaSource>, at: Int) {
        if (list.isEmpty()) return
        playlist = list
        index = at.coerceIn(0, list.lastIndex)
        screen = Screen.Player
    }

    /** Command-line / "Open with…" / drag-and-drop arguments: files, folders, URLs, `--header "K: V"`. */
    fun openArgs(args: List<String>) {
        val headers = LinkedHashMap<String, String>()
        val targets = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--header" && i + 1 < args.size) {
                val h = args[i + 1]
                if (':' in h) headers[h.substringBefore(':').trim()] = h.substringAfter(':').trim()
                i += 2
                continue
            }
            if (!a.startsWith("--")) targets += a
            i++
        }
        val first = targets.firstOrNull() ?: return
        when {
            first.startsWith("http://") || first.startsWith("https://") -> openUrl(first, headers)
            targets.size > 1 -> {
                val files = targets.map(::File).filter { it.isFile && isVideoName(it.name) }
                returnToFolder = false
                play(files.map { MediaSource.File(it.absolutePath) }, 0)
            }
            else -> open(File(first))
        }
    }

    fun open(file: File) {
        when {
            file.isDirectory -> openFolder(file)
            !file.exists() -> message = "Couldn't find ${file.path}"
            isSubtitleName(file.name) -> {
                // A subtitle file opens the video it belongs to (the subtitle loads automatically).
                val video = file.parentFile?.listFiles()?.firstOrNull { v ->
                    isVideoName(v.name) && file.name.lowercase().startsWith(v.nameWithoutExtension.lowercase())
                }
                if (video != null) open(video) else message = "No video named like ${file.name} in its folder. Open the video, then load the subtitles from the player."
            }
            else -> {
                returnToFolder = false
                // Play the whole folder in order, starting at this file, so Next/auto-play work.
                val siblings = folderVideos(file.parentFile)
                val at = siblings.indexOfFirst { it.absolutePath == file.absolutePath }
                if (at >= 0) play(siblings.map { MediaSource.File(it.absolutePath) }, at)
                else play(listOf(MediaSource.File(file.absolutePath)), 0)
            }
        }
    }

    fun openFolder(dir: File) {
        val videos = folderVideos(dir)
        if (videos.isEmpty()) {
            message = "No videos in ${dir.name}"
            return
        }
        folderName = dir.name.ifBlank { dir.absolutePath }
        folderPath = dir.absolutePath
        savedPlaylistId = null
        folderPlaylist = videos.map { MediaSource.File(it.absolutePath) }
        screen = Screen.Folder
    }

    fun openSavedPlaylist(id: String) {
        val saved = settings.current.playlists.firstOrNull { it.id == id } ?: return
        folderName = saved.name
        folderPath = null
        savedPlaylistId = id
        folderPlaylist = saved.files.map { MediaSource.File(it) }
        screen = Screen.Folder
    }

    fun playFolderItem(at: Int) {
        returnToFolder = true
        play(folderPlaylist, at)
    }

    fun openUrl(url: String, headers: Map<String, String> = emptyMap()) {
        returnToFolder = false
        play(listOf(MediaSource.Http(url.trim(), headers)), 0)
    }

    fun openRecent(item: RecentItem) {
        if (item.location.startsWith("http://") || item.location.startsWith("https://")) openUrl(item.location) else open(File(item.location))
    }

    private fun folderVideos(dir: File?): List<File> =
        dir?.listFiles()?.filter { it.isFile && isVideoName(it.name) }?.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.orEmpty()

    // ---- Building what the player shows ----

    /** Builds the player item: title from the release name, and matching subtitle files. Reads the disk. */
    fun buildItem(source: MediaSource): PlayerItem {
        val key = keyOf(source)
        return when (source) {
            is MediaSource.File -> {
                val file = File(source.path)
                val guess = MediaGuesser.guess(file.name, folderName = file.parentFile?.name)
                val title = guess.title?.let { t -> if (guess.episode != null) "$t · Episode ${guess.episode}" else t } ?: file.nameWithoutExtension
                PlayerItem(key, source, title, file.name.takeIf { it != title }, guess, sidecarSubtitles(file))
            }
            else -> {
                val name = source.displayName
                val guess = MediaGuesser.guess(name, url = (source as? MediaSource.Http)?.url)
                PlayerItem(key, source, name.substringBeforeLast('.'), (source as? MediaSource.Http)?.url?.substringBefore('?'), guess)
            }
        }
    }

    fun keyOf(source: MediaSource): String = when (source) {
        is MediaSource.File -> File(source.path).absolutePath
        is MediaSource.Http -> source.url
        is MediaSource.ContentUri -> source.uri
    }

    /**
     * Subtitle files for [video]: same name next to it ("Show 05.en.srt"), in a Subs/ or Subtitles/
     * folder (by name, a per-video subfolder, or episode number), or in the subtitle folder the user
     * picked for this video's folder (by episode number).
     */
    private fun sidecarSubtitles(video: File): List<SubtitleTrack> {
        val prefs = settings.current
        if (!prefs.autoLoadSubtitleFiles && prefs.subtitleFolders[video.parentFile?.absolutePath] == null) return emptyList()
        val dir = video.parentFile ?: return emptyList()
        val lang = prefs.subtitleLanguage
        val found = ArrayList<Pair<File, String?>>()
        fun names(d: File) = d.list()?.toList().orEmpty()

        if (prefs.autoLoadSubtitleFiles) {
            SubtitleMatcher.match(video.name, names(dir), lang, byEpisode = false).forEach { found += File(dir, it.name) to it.language }
            val subDirs = dir.listFiles()?.filter { it.isDirectory && it.name.lowercase() in SUB_FOLDERS }.orEmpty()
            for (sd in subDirs) {
                // Subs/<video name>/*.srt (common for releases with many languages).
                File(sd, video.nameWithoutExtension).takeIf { it.isDirectory }?.let { per ->
                    names(per).filter(::isSubtitleName).sortedWith(NaturalOrder).forEach { n -> found += File(per, n) to languageGuess(n) }
                }
                SubtitleMatcher.match(video.name, names(sd), lang, byEpisode = true).forEach { found += File(sd, it.name) to it.language }
            }
        }
        prefs.subtitleFolders[dir.absolutePath]?.let(::File)?.takeIf { it.isDirectory }?.let { sd ->
            SubtitleMatcher.match(video.name, names(sd), lang, byEpisode = true).forEach { found += File(sd, it.name) to it.language }
        }

        return found.distinctBy { it.first.absolutePath }.take(8).mapNotNull { (f, language) ->
            runCatching {
                val label = f.nameWithoutExtension.removePrefix(video.nameWithoutExtension).trim('.', ' ', '_', '-').ifBlank { f.nameWithoutExtension }
                SubtitleParser.parse(decodeSubtitleText(f.readBytes()), label, language, f.name)
            }.getOrNull()
        }
    }

    private fun languageGuess(name: String): String? =
        SubtitleMatcher.match("x.mkv", listOf("x.$name"), null, byEpisode = false).firstOrNull()?.language

    private companion object {
        val SUB_FOLDERS = setOf("subs", "sub", "subtitles", "subtitle")
    }
}
