package app.auriel.midas.library

import app.auriel.midas.core.Languages
import app.auriel.midas.subtitles.MediaGuesser
import app.auriel.midas.subtitles.SubtitleParser

val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "wmv", "flv", "ogv", "mpg", "mpeg", "3gp", "vob")

fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
fun isSubtitleName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in SubtitleParser.fileExtensions

/**
 * File-explorer order: digit runs compare as numbers, so "E2" comes before "E10".
 * Case-insensitive, with the plain string as a tiebreak so the order is total.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}

/** A subtitle file found next to a video. */
data class SubtitleMatch(val name: String, val language: String?, val reason: Reason) {
    enum class Reason { SameName, Episode }
}

object SubtitleMatcher {
    /**
     * Subtitle files among [candidates] (file names) that belong to [videoName]:
     * - the same base name, optionally with a language tag: "Show 05.srt", "Show 05.en.ass", "Show 05.eng.forced.srt";
     * - otherwise, when [byEpisode] is set (a folder of subtitles for a whole season), the file whose
     *   episode number matches the video's.
     * Best match first: preferred [language], then full subtitles over forced/signs-only.
     */
    fun match(videoName: String, candidates: List<String>, language: String?, byEpisode: Boolean): List<SubtitleMatch> {
        val base = videoName.substringBeforeLast('.').lowercase()
        val subs = candidates.filter(::isSubtitleName)
        val same = subs.mapNotNull { name ->
            val stem = name.substringBeforeLast('.').lowercase()
            if (stem != base && !stem.startsWith("$base.") && !stem.startsWith("${base}_")) return@mapNotNull null
            SubtitleMatch(name, languageTag(stem.removePrefix(base)), SubtitleMatch.Reason.SameName)
        }
        val found = if (same.isNotEmpty() || !byEpisode) {
            same
        } else {
            val video = MediaGuesser.parseReleaseName(videoName)
            val episode = video.episode ?: return emptyList()
            subs.mapNotNull { name ->
                val r = MediaGuesser.parseReleaseName(name)
                if (r.episode != episode) return@mapNotNull null
                if (video.season != null && r.season != null && r.season != video.season) return@mapNotNull null
                SubtitleMatch(name, languageTag(name.substringBeforeLast('.').lowercase()), SubtitleMatch.Reason.Episode)
            }
        }
        val preferred = Languages.normalize(language)
        return found.sortedWith(
            compareBy<SubtitleMatch> { if (preferred != null && it.language == preferred) 0 else if (it.language == null) 1 else 2 }
                .thenBy { if (Regex("""forced|signs""", RegexOption.IGNORE_CASE).containsMatchIn(it.name)) 1 else 0 }
                .thenBy { if (it.name.endsWith(".ass", true) || it.name.endsWith(".ssa", true)) 0 else 1 }
                .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) },
        )
    }

    /** "en", "eng", "english", "ja" etc. as a separate dotted/bracketed token in [stemPart]. */
    private fun languageTag(stemPart: String): String? =
        Regex("""[._\[\( -]([a-z]{2,3}|english|japanese|spanish|french|german|portuguese|italian|russian|chinese|korean)(?=[._\]\) -]|$)""")
            .findAll(stemPart)
            .mapNotNull { m -> Languages.normalize(m.groupValues[1])?.takeIf { Languages.name(it) != it } }
            .firstOrNull()
}
