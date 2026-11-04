package app.auriel.midas.subtitles

/** One line (or block) of subtitles. Times are in seconds. [top]: drawn at the top (signs, `\an8`). */
data class SubtitleCue(
    val start: Double,
    val end: Double,
    val text: String,
    val top: Boolean = false,
    /** Picture subtitles (PGS, VobSub, DVB) carry an image instead of text. */
    val bitmap: SubtitleBitmap? = null,
)

/**
 * A picture subtitle: [pixels] are BGRA (premultiplied) for a [width]×[height] box placed at ([x], [y])
 * on a [canvasWidth]×[canvasHeight] canvas, which maps onto the whole video picture.
 */
class SubtitleBitmap(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val canvasWidth: Int,
    val canvasHeight: Int,
)

/** A parsed subtitle file, ready to display. */
class SubtitleTrack(
    val label: String,
    val language: String?,
    cues: List<SubtitleCue>,
    /** The original script, for ASS/SSA files, so a full renderer (libass) can draw them with their styles. */
    val assScript: String? = null,
) {
    val cues: List<SubtitleCue> = cues.filter { it.end > it.start && (it.text.isNotBlank() || it.bitmap != null) }.sortedBy { it.start }
    private val longest = this.cues.maxOfOrNull { it.end - it.start } ?: 0.0

    /** Cues showing at [time] seconds, in file order. */
    fun activeAt(time: Double): List<SubtitleCue> {
        // Last cue that has started, then walk back over any that could still be on screen.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= time) { last = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (last < 0) return emptyList()
        val out = ArrayList<SubtitleCue>(2)
        var i = last
        while (i >= 0 && cues[i].start >= time - longest) {
            if (cues[i].end > time) out += cues[i]
            i--
        }
        out.reverse()
        return out
    }
}

enum class SubtitleFormat { Srt, WebVtt, Ass, YouTubeXml, YouTubeJson }

object SubtitleParser {
    val fileExtensions = setOf("srt", "vtt", "ass", "ssa")

    fun detect(text: String, fileName: String? = null): SubtitleFormat? {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').take(512)
        return when {
            head.startsWith("WEBVTT") -> SubtitleFormat.WebVtt
            head.startsWith("[Script Info]", ignoreCase = true) || "[Events]" in text.take(20_000) && "Dialogue:" in text ->
                SubtitleFormat.Ass
            head.startsWith("<?xml") || head.startsWith("<transcript") || head.startsWith("<timedtext") ->
                SubtitleFormat.YouTubeXml
            head.startsWith("{") && "\"events\"" in text.take(4096) -> SubtitleFormat.YouTubeJson
            SRT_TIME.containsMatchIn(head) || SRT_TIME.containsMatchIn(text.take(4096)) -> SubtitleFormat.Srt
            else -> when (fileName?.substringAfterLast('.', "")?.lowercase()) {
                "srt" -> SubtitleFormat.Srt
                "vtt" -> SubtitleFormat.WebVtt
                "ass", "ssa" -> SubtitleFormat.Ass
                else -> null
            }
        }
    }

    /** Parses any supported format. Throws [IllegalArgumentException] if it isn't one. */
    fun parse(text: String, label: String, language: String? = null, fileName: String? = null): SubtitleTrack {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when (detect(clean, fileName)) {
            SubtitleFormat.Srt -> parseSrt(clean)
            SubtitleFormat.WebVtt -> parseVtt(clean)
            SubtitleFormat.Ass -> parseAss(clean)
            SubtitleFormat.YouTubeXml -> parseYouTubeXml(clean)
            SubtitleFormat.YouTubeJson -> parseYouTubeJson(clean)
            null -> throw IllegalArgumentException("Not a subtitle file Midas can read (SRT, WebVTT, ASS/SSA)")
        }
        require(cues.isNotEmpty()) { "The subtitle file has no lines" }
        return SubtitleTrack(label, language, cues, assScript = clean.takeIf { detect(clean, fileName) == SubtitleFormat.Ass })
    }

    // ---- SRT ----

    private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    internal fun parseSrt(text: String): List<SubtitleCue> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.trim('\n').lines()
        val timeIndex = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
        if (timeIndex < 0) return@mapNotNull null
        val m = SRT_TIME.find(lines[timeIndex])!!.groupValues
        val start = hms(m[1], m[2], m[3], m[4])
        val end = hms(m[5], m[6], m[7], m[8])
        val raw = lines.drop(timeIndex + 1).joinToString("\n")
        val top = Regex("""\{\\an[789]}""").containsMatchIn(raw)
        SubtitleCue(start, end, stripMarkup(raw.replace(Regex("""\{\\[^}]*}"""), "")), top)
    }

    // ---- WebVTT ----

    private val VTT_TIME = Regex("""((?:\d+:)?\d{2}:\d{2}\.\d{3})\s*-->\s*((?:\d+:)?\d{2}:\d{2}\.\d{3})(.*)""")

    internal fun parseVtt(text: String): List<SubtitleCue> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.trim('\n').lines()
        val timeIndex = lines.indexOfFirst { VTT_TIME.containsMatchIn(it) }
        if (timeIndex < 0) return@mapNotNull null
        val m = VTT_TIME.find(lines[timeIndex])!!.groupValues
        val settings = m[3]
        // "line:0" / "line:10%" style positions near the top.
        val line = Regex("""line:(-?\d+)(%?)""").find(settings)?.groupValues
        val top = line != null && (if (line[2] == "%") line[1].toInt() < 30 else line[1].toInt() in 0..3)
        val body = lines.drop(timeIndex + 1).joinToString("\n")
        SubtitleCue(vttTime(m[1]), vttTime(m[2]), stripMarkup(body), top)
    }

    private fun vttTime(s: String): Double {
        val parts = s.split(':')
        val sec = parts.last().toDouble()
        return when (parts.size) {
            3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + sec
            else -> parts[0].toInt() * 60 + sec
        }
    }

    // ---- ASS / SSA ----

    internal fun parseAss(text: String): List<SubtitleCue> {
        var section = ""
        var styleFormat: List<String> = emptyList()
        var eventFormat = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val topStyles = HashSet<String>()
        val out = ArrayList<SubtitleCue>()
        val seen = HashSet<Triple<Double, Double, String>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) { section = line.lowercase(); continue }
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trimStart()
            when {
                key == "format" && "styles" in section -> styleFormat = value.split(',').map { it.trim().lowercase() }
                key == "style" && "styles" in section -> {
                    val f = value.split(',')
                    val name = f.getOrNull(styleFormat.indexOf("name"))?.trim() ?: continue
                    val align = f.getOrNull(styleFormat.indexOf("alignment"))?.trim()?.toIntOrNull()
                    // V4+ uses numpad alignment (7-9 top); old SSA uses 5-7 for top.
                    val isTop = if ("v4+" in section) align in 7..9 else align in 5..7
                    if (isTop) topStyles += name
                }
                key == "format" && "events" in section -> eventFormat = value.split(',').map { it.trim().lowercase() }
                key == "dialogue" && "events" in section -> {
                    val fields = value.split(',', limit = eventFormat.size)
                    if (fields.size < eventFormat.size) continue
                    fun field(name: String) = fields.getOrNull(eventFormat.indexOf(name))?.trim()
                    val start = assTime(field("start") ?: continue) ?: continue
                    val end = assTime(field("end") ?: continue) ?: continue
                    val raw = fields.last()
                    // Vector drawings (\p1...) aren't text.
                    if (Regex("""\\p[1-9]""").containsMatchIn(raw)) continue
                    val positioned = Regex("""\\(an[789]|pos\(|move\()""").containsMatchIn(raw) ||
                        Regex("""\\a([5-7])\b""").containsMatchIn(raw)
                    val body = raw
                        .replace(Regex("""\{[^}]*}"""), "")
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .lines().joinToString("\n") { it.trim() }.trim()
                    if (body.isEmpty()) continue
                    // Typesetting often stacks the same line several times for effects.
                    if (!seen.add(Triple(start, end, body))) continue
                    out += SubtitleCue(start, end, body, top = positioned || field("style") in topStyles)
                }
            }
        }
        return out
    }

    /** Names of the styles drawn at the top of the screen, from an ASS header's styles section. */
    fun assTopStyles(header: String): Set<String> {
        var section = ""
        var format: List<String> = emptyList()
        val out = HashSet<String>()
        for (rawLine in header.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) { section = line.lowercase(); continue }
            if ("styles" !in section) continue
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trimStart()
            if (key == "format") format = value.split(',').map { it.trim().lowercase() }
            if (key == "style") {
                val f = value.split(',')
                val name = f.getOrNull(format.indexOf("name"))?.trim() ?: continue
                val align = f.getOrNull(format.indexOf("alignment"))?.trim()?.toIntOrNull()
                if (if ("v4+" in section) align in 7..9 else align in 5..7) out += name
            }
        }
        return out
    }

    /**
     * One event as FFmpeg's decoders hand it out: "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text".
     * Reuses the file parser so positioning and tag stripping match loaded .ass files exactly.
     */
    fun assEventCue(start: Double, end: Double, event: String, topStyles: Set<String>): SubtitleCue? {
        val fields = event.trim().removePrefix("Dialogue:").trimStart().split(',', limit = 9)
        if (fields.size < 9) return null
        val style = fields[2].trim()
        val line = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:00.00,0:00:01.00,$style,,0,0,0,,${fields[8]}"
        val cue = parseAss(line).firstOrNull() ?: return null
        return SubtitleCue(start, end, cue.text, top = cue.top || style in topStyles)
    }

    private fun assTime(s: String): Double? {
        val m = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})""").find(s)?.groupValues ?: return null
        return hms(m[1], m[2], m[3], m[4])
    }

    // ---- YouTube timedtext ----

    /** srv1 (`<text start dur>`, seconds) and srv3 (`<p t d>`, milliseconds). */
    internal fun parseYouTubeXml(text: String): List<SubtitleCue> {
        val srv1 = Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        val srv3 = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        fun attr(attrs: String, name: String) = Regex("""\b$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
        val cues = srv1.findAll(text).mapNotNull { m ->
            val start = attr(m.groupValues[1], "start") ?: return@mapNotNull null
            val dur = attr(m.groupValues[1], "dur") ?: 2.0
            SubtitleCue(start, start + dur, xmlText(m.groupValues[2]))
        }.toList().ifEmpty {
            srv3.findAll(text).mapNotNull { m ->
                val start = attr(m.groupValues[1], "t") ?: return@mapNotNull null
                val dur = attr(m.groupValues[1], "d") ?: 2000.0
                SubtitleCue(start / 1000, (start + dur) / 1000, xmlText(m.groupValues[2].replace(Regex("<[^>]+>"), "")))
            }.toList()
        }
        return trimOverlaps(cues)
    }

    internal fun parseYouTubeJson(text: String): List<SubtitleCue> {
        val event = Regex(""""tStartMs"\s*:\s*(\d+)\s*,\s*"dDurationMs"\s*:\s*(\d+)[^{}]*"segs"\s*:\s*\[(.*?)]\s*}""", RegexOption.DOT_MATCHES_ALL)
        val seg = Regex(""""utf8"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val cues = event.findAll(text).map { m ->
            val body = seg.findAll(m.groupValues[3]).joinToString("") { jsonUnescape(it.groupValues[1]) }
            SubtitleCue(m.groupValues[1].toDouble() / 1000, (m.groupValues[1].toDouble() + m.groupValues[2].toDouble()) / 1000, body.trim())
        }.toList()
        return trimOverlaps(cues)
    }

    /** Auto-generated captions overlap each other; show one line at a time instead of stacking them. */
    private fun trimOverlaps(cues: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.start }
        return sorted.mapIndexed { i, c ->
            val next = sorted.getOrNull(i + 1)
            if (next != null && next.start < c.end && next.start > c.start) c.copy(end = next.start) else c
        }
    }

    // ---- helpers ----

    private fun hms(h: String, m: String, s: String, frac: String): Double {
        val fraction = when (frac.length) {
            1 -> frac.toInt() / 10.0
            2 -> frac.toInt() / 100.0
            else -> frac.toInt() / 1000.0
        }
        return h.toInt() * 3600 + m.toInt() * 60 + s.toInt() + fraction
    }

    /** Drops HTML-ish tags (<i>, <c.color>, karaoke timestamps) and decodes entities. */
    private fun stripMarkup(s: String): String =
        htmlUnescape(s.replace(Regex("""<[^>]*>"""), "")).lines().joinToString("\n") { it.trim() }.trim()

    /** YouTube's srv1 escapes entities twice ("&amp;#39;"). */
    private fun xmlText(s: String): String = htmlUnescape(htmlUnescape(s)).trim()

    internal fun htmlUnescape(s: String): String {
        if ('&' !in s) return s
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp);""").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    codePointToString(code)
                }
            }
        }
    }

    private fun jsonUnescape(s: String): String = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("u") -> e.drop(1).toInt(16).toChar().toString()
            e == "n" -> "\n"
            e == "t" -> "\t"
            else -> e
        }
    }

    private fun codePointToString(code: Int): String = if (code < 0x10000) {
        code.toChar().toString()
    } else {
        val c = code - 0x10000
        charArrayOf((0xD800 + (c shr 10)).toChar(), (0xDC00 + (c and 0x3FF)).toChar()).concatToString()
    }
}
