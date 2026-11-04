package app.auriel.midas.core

/**
 * Language codes as files store them (ISO 639-2 "jpn", "eng", sometimes 639-1 "ja") and as users type
 * them in settings ("ja", "Japanese"). Everything is compared through [normalize].
 */
object Languages {
    // 639-1 code, display name, then the 639-2 codes (bibliographic and terminology) that mean it.
    private val TABLE: List<Triple<String, String, List<String>>> = listOf(
        Triple("en", "English", listOf("eng")),
        Triple("ja", "Japanese", listOf("jpn")),
        Triple("es", "Spanish", listOf("spa")),
        Triple("pt", "Portuguese", listOf("por")),
        Triple("fr", "French", listOf("fre", "fra")),
        Triple("de", "German", listOf("ger", "deu")),
        Triple("it", "Italian", listOf("ita")),
        Triple("ru", "Russian", listOf("rus")),
        Triple("ar", "Arabic", listOf("ara")),
        Triple("zh", "Chinese", listOf("chi", "zho")),
        Triple("ko", "Korean", listOf("kor")),
        Triple("id", "Indonesian", listOf("ind")),
        Triple("vi", "Vietnamese", listOf("vie")),
        Triple("th", "Thai", listOf("tha")),
        Triple("tr", "Turkish", listOf("tur")),
        Triple("pl", "Polish", listOf("pol")),
        Triple("nl", "Dutch", listOf("dut", "nld")),
        Triple("hi", "Hindi", listOf("hin")),
        Triple("ml", "Malayalam", listOf("mal")),
        Triple("ta", "Tamil", listOf("tam")),
        Triple("sv", "Swedish", listOf("swe")),
        Triple("fi", "Finnish", listOf("fin")),
        Triple("no", "Norwegian", listOf("nor", "nob", "nno")),
        Triple("da", "Danish", listOf("dan")),
        Triple("cs", "Czech", listOf("cze", "ces")),
        Triple("hu", "Hungarian", listOf("hun")),
        Triple("el", "Greek", listOf("gre", "ell")),
        Triple("he", "Hebrew", listOf("heb")),
        Triple("uk", "Ukrainian", listOf("ukr")),
        Triple("ro", "Romanian", listOf("rum", "ron")),
        Triple("ms", "Malay", listOf("may", "msa")),
        Triple("fa", "Persian", listOf("per", "fas")),
    )

    private val byAnyCode: Map<String, Triple<String, String, List<String>>> = buildMap {
        for (row in TABLE) {
            put(row.first, row)
            put(row.second.lowercase(), row)
            row.third.forEach { put(it, row) }
        }
    }

    /** "jpn", "ja", "Japanese", "ja-JP" → "ja". Unknown codes come back lowercased; "und" and blanks → null. */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (c.isEmpty() || c == "und" || c == "unk" || c == "mis" || c == "zxx") return null
        return byAnyCode[c]?.first ?: c
    }

    /** Display name for any code ("jpn" → "Japanese"); unknown codes are returned as given. */
    fun name(code: String): String = byAnyCode[code.trim().lowercase().substringBefore('-')]?.second ?: code

    fun same(a: String?, b: String?): Boolean {
        val na = normalize(a) ?: return false
        return na == normalize(b)
    }
}
