package app.auriel.midas.android.player

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Seekable reader for public MEGA file links. The fragment is used only locally. */
internal class MegaDataSource : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var cipher: Cipher? = null
    private var remaining = 0L
    private var opened = false
    private var uri: Uri? = null

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val link = dataSpec.uri
            val id = link.pathSegments.takeIf { it.size == 2 && it[0] == "file" }?.get(1)
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8}")) } ?: throw IOException("Invalid MEGA file link")
            val key = try { Base64.decode(link.fragment ?: "", Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
                catch (_: IllegalArgumentException) { throw IOException("Invalid MEGA file key") }
            if (key.size != 32) throw IOException("Invalid MEGA file key")
            val api = URL("https://g.api.mega.co.nz/cs?id=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            val metadata = try {
                api.requestMethod = "POST"; api.doOutput = true
                api.connectTimeout = 15000; api.readTimeout = 30000
                api.setRequestProperty("Content-Type", "application/json")
                val request = JSONArray().put(JSONObject().put("a", "g").put("g", 1).put("ssl", 2).put("p", id))
                api.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val result = JSONArray(api.inputStream.bufferedReader().use { it.readText() }).get(0)
                result as? JSONObject ?: throw IOException("MEGA could not open this file ($result)")
            } finally { api.disconnect() }
            val size = metadata.optLong("s", -1)
            if (size < 0) throw IOException("MEGA returned an invalid file size")
            val position = dataSpec.position
            if (position > size) throw IOException("Seek past end of file")
            remaining = minOf(size - position, if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataSpec.length)
            uri = link
            opened = true
            if (remaining == 0L) { transferStarted(dataSpec); return 0L }
            val download = Uri.parse(metadata.optString("g"))
            if (download.scheme != "https" || download.host.isNullOrBlank()) throw IOException("MEGA returned an invalid download URL")
            val aligned = position - position % 16
            val remote = URL(download.toString()).openConnection() as HttpURLConnection
            connection = remote
            remote.connectTimeout = 15000; remote.readTimeout = 30000
            remote.setRequestProperty("Range", "bytes=$aligned-")
            val status = remote.responseCode
            if (status != 206 && !(status == 200 && aligned == 0L)) throw IOException("MEGA download failed (HTTP $status)")
            val aesKey = ByteArray(16) { i -> (key[i].toInt() xor key[i + 16].toInt()).toByte() }
            val iv = ByteArray(16)
            key.copyInto(iv, 0, 16, 24)
            ByteBuffer.wrap(iv, 8, 8).putLong(aligned / 16)
            cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv)) }
            val input = remote.inputStream
            val skip = (position - aligned).toInt()
            if (skip > 0) {
                val prefix = ByteArray(skip)
                var read = 0
                while (read < skip) { val n = input.read(prefix, read, skip - read); if (n < 0) throw IOException("MEGA stream ended early"); read += n }
                cipher!!.update(prefix)
            }
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            connection?.disconnect(); connection = null; cipher = null; uri = null; opened = false
            if (e is IOException) throw e
            throw IOException("Could not open MEGA file", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val data = ByteArray(minOf(length.toLong(), remaining).toInt())
        val n = connection?.inputStream?.read(data) ?: throw IOException("MEGA connection closed")
        if (n < 0) throw IOException("MEGA stream ended early")
        val decrypted = cipher!!.update(data, 0, n)
        decrypted.copyInto(buffer, offset)
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        connection?.disconnect(); connection = null; cipher = null; uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

internal class MegaRoutingDataSource(private val regular: DataSource) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners += listener }
    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host?.lowercase()
        val source = if (host in setOf("mega.nz", "www.mega.nz", "mega.co.nz", "www.mega.co.nz")) MegaDataSource() else regular
        listeners.forEach(source::addTransferListener)
        active = source
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { active?.close(); active = null }
}
