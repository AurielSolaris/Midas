package app.auriel.midas.platform

/** Subtitle bytes to text: honours a BOM, else UTF-8, else Shift_JIS (old Japanese subs) or Windows-1252. */
expect fun decodeSubtitleText(bytes: ByteArray): String

/** Files inside a .zip archive, as (name, bytes). Folders are skipped. */
expect fun unzipEntries(bytes: ByteArray): List<Pair<String, ByteArray>>
