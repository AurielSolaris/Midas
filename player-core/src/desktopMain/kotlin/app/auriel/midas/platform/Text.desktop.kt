package app.auriel.midas.platform

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

actual fun decodeSubtitleText(bytes: ByteArray): String {
    fun strict(charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    return when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> strict(Charsets.UTF_8)
            ?: runCatching { strict(Charset.forName("Shift_JIS")) }.getOrNull()
            ?: String(bytes, Charset.forName("windows-1252"))
    }
}

actual fun unzipEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
    val out = ArrayList<Pair<String, ByteArray>>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) out += entry.name.substringAfterLast('/') to zip.readBytes()
        }
    }
    return out
}
