package app.auriel.midas.subtitles

import app.auriel.midas.core.Languages
import app.auriel.midas.data.MidasSettings
import app.auriel.midas.platform.decodeSubtitleText
import app.auriel.midas.platform.unzipEntries
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ---------------------------------------------------------------------------------------------
// What is this video?
// ---------------------------------------------------------------------------------------------

/** What a video probably is, guessed from its Hydrus tags and URLs, for finding subtitles. */
data class MediaGuess(
    val youtubeId: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class ReleaseName(val title: String?, val season: Int?, val episode: Int?)

object MediaGuesser {
    private val YOUTUBE_URL = Regex(
        """(?:youtube\.com/(?:watch\?(?:[^#\s]*&)?v=|shorts/|embed/|live/|v/)|youtu\.be/|youtube-nocookie\.com/embed/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""",
        RegexOption.IGNORE_CASE,
    )

    /** yt-dlp's default file name: "Title [dQw4w9WgXcQ].mp4". */
    private val YT_DLP_ID = Regex("""\[([A-Za-z0-9_-]{11})](?:\.\w{2,4})?\s*$""")
    private val BARE_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    /**
     * Guesses from the file name (release-style names like "[Group] Show - 05 (1080p).mkv" or yt-dlp's
     * "Title [id].mp4"), the source URL, and the parent folder name when the file itself is just "05.mkv".
     */
    fun guess(fileName: String, url: String? = null, folderName: String? = null): MediaGuess {
        val youtube = url?.let(::youtubeIdFromUrl) ?: YT_DLP_ID.find(fileName)?.groupValues?.get(1)
        val release = parseReleaseName(fileName)
        val folder = folderName?.let(::parseReleaseName)
        val title = release.title?.takeUnless { it.all(Char::isDigit) } ?: folder?.title
        return MediaGuess(
            youtubeId = youtube,
            title = title?.takeIf { it.isNotBlank() },
            season = release.season ?: folder?.season,
            episode = release.episode ?: fileName.substringBeforeLast('.').trim().toIntOrNull(),
        )
    }

    /** Video id from a YouTube URL, or from a bare 11-character id. */
    fun youtubeIdFromUrl(text: String): String? {
        val t = text.trim()
        YOUTUBE_URL.find(t)?.let { return it.groupValues[1] }
        return t.takeIf { BARE_ID.matches(it) && it.any(Char::isLetter) }
    }

    private val VIDEO_EXT = Regex("""\.(mkv|mp4|m4v|webm|avi|mov|ts|wmv|flv|srt|ass|ssa|vtt)$""", RegexOption.IGNORE_CASE)
    private val NOISE = Regex(
        """\b(\d{3,4}p|[248]k|x26[45]|h\.?26[45]|hevc|avc|aac\d?(\.\d)?|flac|opus|ddp?\d?(\.\d)?|10-?bit|8-?bit|hdr|""" +
            """web-?(dl|rip)?|bd(rip)?|blu-?ray|dual[ -]audio|multi-?subs?|uncensored|batch|complete)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Title / season / episode from a release name like
     * "[SubsPlease] Sousou no Frieren - 05 (1080p) [A1B2C3D4].mkv" or "Show.Name.S01E05.1080p.WEB.mkv".
     */
    fun parseReleaseName(raw: String): ReleaseName {
        var s = raw.substringAfterLast('/').substringAfterLast('\\').replace(VIDEO_EXT, "")
        s = s.replace(Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}|【[^】]*】"""), " ")
        s = if (' ' !in s.trim()) s.replace('.', ' ').replace('_', ' ') else s.replace('_', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()

        var title: String = s
        var season: Int? = null
        var episode: Int? = null
        val patterns = listOf(
            Regex("""\bS(\d{1,2}) ?E(\d{1,4})\b""", RegexOption.IGNORE_CASE) to true,
            Regex("""\s[-–]\s(?:E|EP)?(\d{1,4})(?:v\d)?\b""", RegexOption.IGNORE_CASE) to false,
            Regex("""\b(?:E|EP|Ep\.|Episode|第)\s?(\d{1,4})(?:v\d)?\b""", RegexOption.IGNORE_CASE) to false,
            Regex("""\s(\d{1,3})(?:v\d)?(?=\s|$)""") to false,
        )
        for ((re, hasSeason) in patterns) {
            val m = re.find(s) ?: continue
            if (hasSeason) {
                season = m.groupValues[1].toInt()
                episode = m.groupValues[2].toInt()
            } else {
                episode = m.groupValues[1].toInt()
            }
            title = s.substring(0, m.range.first)
            break
        }
        if (season == null) {
            Regex("""\b(?:Season|S)\s?(\d{1,2})\b|\b(\d)(?:st|nd|rd|th) Season\b""", RegexOption.IGNORE_CASE).find(title)?.let {
                season = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull()
            }
        }
        title = title.replace(NOISE, " ").replace(Regex("\\s+"), " ").trim(' ', '-', '–', '.', '_', '~')
        return ReleaseName(title.ifBlank { null }, season, episode)
    }
}

// ---------------------------------------------------------------------------------------------
// Fetching
// ---------------------------------------------------------------------------------------------

/** A subtitle found online. It's only downloaded when picked. */
class RemoteSubtitle(
    val provider: String,
    val title: String,
    val detail: String,
    val language: String?,
    val download: suspend () -> SubtitleTrack,
)

/** One provider's answer: its results, or why it failed. */
data class ProviderResult(val provider: String, val items: List<RemoteSubtitle>, val error: String? = null)

data class AnimeQuery(val title: String, val season: Int?, val episode: Int?, val language: String)

class SubtitleException(message: String) : Exception(message)

/**
 * Where Midas looks for subtitles.
 *
 * - YouTube: the Innertube player API (as the Android app calls it) lists caption tracks, including
 *   auto-generated ones; each track's timedtext URL downloads as srv1 XML. No key needed.
 * - Anime: AniList (no key) resolves the show's id and alternative titles. Then Jimaku
 *   (jimaku.cc, anime-only, mostly Japanese + English), OpenSubtitles and SubDL are searched, each
 *   with the user's own free API key.
 * - Any direct link to an .srt/.vtt/.ass file.
 */
class SubtitleFetcher(private val http: HttpClient, private val settings: () -> MidasSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    val animeProviders: List<String>
        get() = buildList {
            val s = settings()
            if (s.jimakuApiKey.isNotBlank()) add(JIMAKU)
            if (s.openSubtitlesApiKey.isNotBlank()) add(OPENSUBTITLES)
            if (s.subDlApiKey.isNotBlank()) add(SUBDL)
        }

    // ---- Direct link ----

    suspend fun fromUrl(url: String): SubtitleTrack {
        val response = send { http.get(url.trim()) }
        val bytes = response.bodyAsBytes()
        val name = url.substringBefore('?').substringAfterLast('/')
        return parseDownloaded(name, bytes, episode = null, label = name.ifBlank { "Subtitles" }, language = null)
    }

    // ---- YouTube ----

    suspend fun youtube(videoId: String): List<RemoteSubtitle> {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID")
                    put("clientVersion", YT_ANDROID_VERSION)
                    put("androidSdkVersion", 34)
                    put("hl", "en")
                }
            }
            put("videoId", videoId)
        }
        val response = send {
            http.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false") {
                header("User-Agent", "com.google.android.youtube/$YT_ANDROID_VERSION (Linux; U; Android 14) gzip")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val root = json.parseToJsonElement(response.bodyAsText()).obj()
        val renderer = root?.path("captions", "playerCaptionsTracklistRenderer")
        val tracks = renderer?.get("captionTracks").arr()
        if (tracks.isEmpty()) {
            val status = root?.path("playabilityStatus")
            val reason = status?.str("reason") ?: status?.str("status")?.takeIf { it != "OK" }
            throw SubtitleException(if (reason != null) "YouTube: $reason" else "This video has no captions on YouTube.")
        }
        val preferred = settings().subtitleLanguage.lowercase()

        data class Track(val url: String, val lang: String, val name: String, val auto: Boolean, val translatable: Boolean)
        val parsed = tracks.mapNotNull { t ->
            val o = t.obj() ?: return@mapNotNull null
            val url = o.str("baseUrl") ?: return@mapNotNull null
            val nameObj = o["name"].obj()
            val name = nameObj?.str("simpleText") ?: nameObj?.get("runs").arr().firstOrNull()?.obj()?.str("text")
            val lang = o.str("languageCode") ?: ""
            Track(url, lang, name ?: lang, o.str("kind") == "asr", o["isTranslatable"].bool() == true)
        }

        fun item(t: Track, label: String, url: String) = RemoteSubtitle(
            provider = YOUTUBE,
            title = label,
            detail = if (t.auto) "Auto-generated" else "Uploaded captions",
            language = t.lang,
        ) {
            // Without fmt YouTube returns srv1 XML: one clean line per caption, even for auto captions.
            val text = send { http.get(url.replace(Regex("&fmt=[^&]*"), "")) }.bodyAsText()
            SubtitleParser.parse(text, label, t.lang)
        }

        val items = parsed.map { item(it, it.name, it.url) }.toMutableList()
        // No caption in the preferred language: offer YouTube's machine translation of the best track.
        if (parsed.none { it.lang.lowercase().startsWith(preferred) }) {
            val source = parsed.filter { it.translatable }.minByOrNull { if (it.auto) 1 else 0 }
            if (source != null) {
                items += item(source, "${languageName(preferred)} (translated from ${source.name})", source.url + "&tlang=$preferred")
            }
        }
        return items.sortedWith(
            compareBy<RemoteSubtitle> { if (it.language?.lowercase()?.startsWith(preferred) == true || it.title.contains("translated")) 0 else 1 }
                .thenBy { if (it.detail == "Auto-generated") 1 else 0 },
        )
    }

    // ---- Anime ----

    data class AniListMatch(val id: Int, val titles: List<String>)

    /** Searches every provider that has a key, at once. AniList adds the show's id and other titles. */
    suspend fun anime(query: AnimeQuery): Pair<AniListMatch?, List<ProviderResult>> = coroutineScope {
        val anilist = runCatching { aniList(query.title) }.getOrNull()
        val providers = animeProviders
        if (providers.isEmpty()) {
            throw SubtitleException("Add a Jimaku, OpenSubtitles or SubDL API key in Settings → Subtitles to search for anime subtitles.")
        }
        val results = providers.map { p ->
            async {
                try {
                    val items = when (p) {
                        JIMAKU -> jimaku(query, anilist)
                        OPENSUBTITLES -> openSubtitles(query, anilist)
                        else -> subDl(query, anilist)
                    }
                    ProviderResult(p, items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProviderResult(p, emptyList(), e.message ?: e.toString())
                }
            }
        }.awaitAll()
        anilist to results
    }

    private suspend fun aniList(title: String): AniListMatch? {
        val body = buildJsonObject {
            put("query", "query(\$s:String){Media(search:\$s,type:ANIME){id synonyms title{romaji english native}}}")
            putJsonObject("variables") { put("s", title) }
        }
        val response = send {
            http.post("https://graphql.anilist.co") {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                setBody(body.toString())
            }
        }
        val media = json.parseToJsonElement(response.bodyAsText()).obj()?.path("data", "Media") ?: return null
        val id = media["id"].int() ?: return null
        val t = media["title"].obj()
        val titles = listOfNotNull(t?.str("romaji"), t?.str("english")) + media["synonyms"].arr().mapNotNull { it.prim() }
        return AniListMatch(id, titles.distinct())
    }

    private suspend fun jimaku(q: AnimeQuery, anilist: AniListMatch?): List<RemoteSubtitle> {
        val key = settings().jimakuApiKey.trim()
        suspend fun search(block: HttpRequestBuilder.() -> Unit): JsonArray = json.parseToJsonElement(
            send {
                http.get("https://jimaku.cc/api/entries/search") {
                    header("Authorization", key)
                    parameter("anime", true)
                    block()
                }
            }.bodyAsText(),
        ).arr()

        var entries = if (anilist != null) search { parameter("anilist_id", anilist.id) } else JsonArray(emptyList())
        if (entries.isEmpty()) entries = search { parameter("query", q.title) }
        return entries.take(3).mapNotNull { it.obj() }.flatMap { entry ->
            val id = entry["id"].long() ?: return@flatMap emptyList()
            val entryName = entry.str("english_name") ?: entry.str("name") ?: "Jimaku #$id"
            val files = json.parseToJsonElement(
                send {
                    http.get("https://jimaku.cc/api/entries/$id/files") {
                        header("Authorization", key)
                        if (q.episode != null) parameter("episode", q.episode)
                    }
                }.bodyAsText(),
            ).arr()
            files.mapNotNull { f ->
                val o = f.obj() ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                val url = o.str("url") ?: return@mapNotNull null
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in SubtitleParser.fileExtensions && ext != "zip") return@mapNotNull null
                val lang = languageFromFileName(name)
                RemoteSubtitle(JIMAKU, name, listOfNotNull(entryName, o["size"].long()?.let(::kb), lang?.let(::languageName)).joinToString(" · "), lang) {
                    val bytes = send { http.get(url) { header("Authorization", key) } }.bodyAsBytes()
                    parseDownloaded(name, bytes, q.episode, name, lang)
                }
            }
        }.sortedBy { if (it.language == q.language) 0 else 1 }
    }

    private suspend fun openSubtitles(q: AnimeQuery, anilist: AniListMatch?): List<RemoteSubtitle> {
        val key = settings().openSubtitlesApiKey.trim()
        suspend fun search(title: String): JsonArray {
            val response = send {
                http.get("$OPENSUBTITLES_API/subtitles") {
                    osHeaders(key)
                    // The API wants parameters sorted and lowercased (otherwise it redirects).
                    if (q.episode != null) parameter("episode_number", q.episode)
                    parameter("languages", q.language.lowercase())
                    parameter("query", title.lowercase())
                    if (q.episode != null) parameter("season_number", q.season ?: 1)
                }
            }
            return json.parseToJsonElement(response.bodyAsText()).obj()?.get("data").arr()
        }

        val titles = (listOf(q.title) + anilist?.titles.orEmpty()).distinctBy { it.lowercase() }.take(3)
        var data = JsonArray(emptyList())
        for (t in titles) {
            data = search(t)
            if (data.isNotEmpty()) break
        }
        return data.mapNotNull { it.obj()?.get("attributes").obj() }.mapNotNull { a ->
            val fileId = a["files"].arr().firstOrNull()?.obj()?.get("file_id").long() ?: return@mapNotNull null
            val feature = a["feature_details"].obj()
            val release = a.str("release")?.ifBlank { null } ?: feature?.str("title") ?: "Subtitle $fileId"
            val lang = a.str("language")
            val detail = listOfNotNull(
                feature?.str("title"),
                feature?.get("episode_number").int()?.let { "Ep $it" },
                lang?.let(::languageName),
                a["download_count"].long()?.let { "$it downloads" },
                "SDH".takeIf { a["hearing_impaired"].bool() == true },
            ).joinToString(" · ")
            RemoteSubtitle(OPENSUBTITLES, release, detail, lang) {
                val link = send {
                    http.post("$OPENSUBTITLES_API/download") {
                        osHeaders(key)
                        contentType(ContentType.Application.Json)
                        setBody("""{"file_id":$fileId}""")
                    }
                }.bodyAsText().let { json.parseToJsonElement(it).obj()?.str("link") }
                    ?: throw SubtitleException("OpenSubtitles didn't return a download link (daily limit reached?)")
                parseDownloaded("$release.srt", send { http.get(link) }.bodyAsBytes(), q.episode, release, lang)
            }
        }
    }

    private fun HttpRequestBuilder.osHeaders(key: String) {
        header("Api-Key", key)
        header("User-Agent", "Midas v0.1.0")
        header("Accept", "application/json")
    }

    private suspend fun subDl(q: AnimeQuery, anilist: AniListMatch?): List<RemoteSubtitle> {
        val key = settings().subDlApiKey.trim()
        suspend fun search(title: String): JsonArray {
            val response = send {
                http.get("https://api.subdl.com/api/v1/subtitles") {
                    parameter("api_key", key)
                    parameter("film_name", title)
                    if (q.episode != null) {
                        parameter("type", "tv")
                        parameter("season_number", q.season ?: 1)
                        parameter("episode_number", q.episode)
                    }
                    parameter("languages", q.language.uppercase())
                    parameter("subs_per_page", 30)
                }
            }
            val root = json.parseToJsonElement(response.bodyAsText()).obj()
            if (root?.get("status")?.bool() == false) {
                val msg = root.str("error") ?: root.str("message")
                // "can't find movie or tv" just means no results for this title.
                if (msg != null && !msg.contains("find", ignoreCase = true)) throw SubtitleException("SubDL: $msg")
            }
            return root?.get("subtitles").arr()
        }

        val titles = (listOf(q.title) + anilist?.titles.orEmpty()).distinctBy { it.lowercase() }.take(3)
        var subs = JsonArray(emptyList())
        for (t in titles) {
            subs = search(t)
            if (subs.isNotEmpty()) break
        }
        return subs.mapNotNull { it.obj() }.mapNotNull { o ->
            val path = o.str("url") ?: return@mapNotNull null
            val release = o.str("release_name") ?: o.str("name") ?: path
            val lang = o.str("language")?.lowercase()
            val detail = listOfNotNull(
                o["episode"].int()?.takeIf { it > 0 }?.let { "Ep $it" } ?: "Season pack".takeIf { o["full_season"].bool() == true },
                lang?.let(::languageName),
                o.str("author")?.takeIf { it.isNotBlank() }?.let { "by $it" },
            ).joinToString(" · ")
            RemoteSubtitle(SUBDL, release, detail, lang) {
                val bytes = send { http.get("https://dl.subdl.com$path") }.bodyAsBytes()
                parseDownloaded(path.substringAfterLast('/'), bytes, q.episode, release, lang)
            }
        }
    }

    // ---- helpers ----

    /** Parses a downloaded file; for a .zip, picks the subtitle matching [episode] (or the first). */
    private fun parseDownloaded(name: String, bytes: ByteArray, episode: Int?, label: String, language: String?): SubtitleTrack {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return SubtitleParser.parse(decodeSubtitleText(bytes), label, language, name)
        val subs = unzipEntries(bytes).filter { it.first.substringAfterLast('.', "").lowercase() in SubtitleParser.fileExtensions }
        if (subs.isEmpty()) throw SubtitleException("The archive has no subtitle files")
        val pick = episode?.let { ep -> subs.firstOrNull { MediaGuesser.parseReleaseName(it.first).episode == ep } }
            ?: subs.firstOrNull { languageFromFileName(it.first) == language }
            ?: subs.first()
        return SubtitleParser.parse(decodeSubtitleText(pick.second), label, language, pick.first)
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SubtitleException("Network error: ${e.message ?: e::class.simpleName}")
        }
        if (!response.status.isSuccess()) {
            val host = response.call.request.url.host.removePrefix("www.").removePrefix("api.")
            val hint = when (response.status.value) {
                401, 403 -> " (check the API key in Settings)"
                406 -> " (download limit reached for today)"
                429 -> " (too many requests, try again in a minute)"
                else -> ""
            }
            throw SubtitleException("$host returned ${response.status.value}$hint")
        }
        return response
    }

    companion object {
        const val YOUTUBE = "YouTube"
        const val JIMAKU = "Jimaku"
        const val OPENSUBTITLES = "OpenSubtitles"
        const val SUBDL = "SubDL"
        private const val OPENSUBTITLES_API = "https://api.opensubtitles.com/api/v1"
        private const val YT_ANDROID_VERSION = "20.10.38"
    }
}

private fun languageFromFileName(name: String): String? {
    val n = name.lowercase()
    return when {
        Regex("""[.\[_ (-](ja|jp|jpn|japanese)[.\]_ )-]""").containsMatchIn(n) || Regex("[぀-ヿ]").containsMatchIn(name) -> "ja"
        Regex("""[.\[_ (-](en|eng|english)[.\]_ )-]""").containsMatchIn(n) -> "en"
        else -> null
    }
}

fun languageName(code: String): String = Languages.name(code)

private fun kb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"

// Small JSON helpers: these APIs are loosely typed, so every field is optional.
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.path(vararg keys: String): JsonObject? =
    keys.fold(this as JsonObject?) { o, k -> o?.get(k) as? JsonObject }
