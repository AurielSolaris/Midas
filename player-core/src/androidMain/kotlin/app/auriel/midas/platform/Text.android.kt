package app.auriel.midas.platform

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

actual fun decodeSubtitleText(bytes: ByteArray): String {
    fun strict(charset: Charset): String? = runCatching {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    return when {
        bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> strict(Charsets.UTF_8) ?: runCatching { strict(Charset.forName("Shift_JIS")) }.getOrNull() ?: String(bytes, Charset.forName("windows-1252"))
    }
}

actual fun unzipEntries(bytes: ByteArray): List<Pair<String, ByteArray>> {
    val result = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) result += entry.name.substringAfterLast('/') to zip.readBytes()
        }
    }
    return result
}
