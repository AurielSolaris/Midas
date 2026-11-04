package app.auriel.midas.desktop

import app.auriel.midas.core.MediaSource
import app.auriel.midas.subtitles.MediaGuesser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** Resolves a watch page to one combined audio/video stream in a background process. */
internal object YouTubeResolver {
    data class Quality(val label: String, val source: MediaSource.Http)
    data class Result(val title: String, val qualities: List<Quality>)
    fun isYouTube(source: MediaSource): Boolean {
        if (source !is MediaSource.Http) return false
        val host = runCatching { java.net.URI(source.url).host?.lowercase() }.getOrNull() ?: return false
        return (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")) &&
            MediaGuesser.youtubeIdFromUrl(source.url) != null
    }

    suspend fun resolve(source: MediaSource.Http): Result {
        val errorFile = File.createTempFile("midas-youtube-", ".log")
        val outputFile = File.createTempFile("midas-youtube-", ".json")
        val command = mutableListOf(
            "yt-dlp", "--ignore-config", "--no-playlist", "--no-warnings", "--socket-timeout", "15",
            "--js-runtimes", "node", "--remote-components", "ejs:github", "--dump-single-json", "--", source.url,
        )
        var process: Process? = null
        try {
            process = try {
                ProcessBuilder(command).redirectError(errorFile).redirectOutput(outputFile).start()
            } catch (_: java.io.IOException) {
                throw IllegalStateException("YouTube playback requires yt-dlp on your PATH. Install or update yt-dlp and a supported JavaScript runtime, then try again.")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw IllegalStateException("YouTube took too long to respond. Please try again.")
            }
            if (process.exitValue() != 0) {
                val detail = errorFile.readLines().lastOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
                throw IllegalStateException("Could not open this YouTube video${detail?.let { ": $it" } ?: ". Update yt-dlp and try again."}")
            }
            val info = Json.parseToJsonElement(outputFile.readText()).jsonObject
            val title = info["title"]?.jsonPrimitive?.content ?: "YouTube video"
            val formats = info["formats"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val f = entry.jsonObject
                val url = f["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val video = f["vcodec"]?.jsonPrimitive?.content
                val audio = f["acodec"]?.jsonPrimitive?.content
                val protocol = f["protocol"]?.jsonPrimitive?.content
                val height = f["height"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                if (!url.startsWith("https://") || protocol != "https" || video == null || video == "none" || audio == null || audio == "none") return@mapNotNull null
                val headers = f["http_headers"]?.jsonObject?.mapNotNull { (key, value) ->
                    val text = value.jsonPrimitive.content
                    if ('\r' in key || '\n' in key || '\r' in text || '\n' in text) null else key to text
                }?.toMap().orEmpty()
                height to MediaSource.Http(url, headers + source.headers)
            }.sortedByDescending { it.first }.distinctBy { it.first }
            if (formats.isEmpty()) throw IllegalStateException("YouTube did not return a combined video and audio stream. Update yt-dlp and try again.")
            return Result(title, formats.map { (height, media) -> Quality("${height}p", StreamForwarder.wrap(media)) })
        } finally {
            process?.destroyForcibly()
            errorFile.delete()
            outputFile.delete()
        }
    }
}
