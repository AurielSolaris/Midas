package app.auriel.midas.desktop

import app.auriel.midas.core.MediaSource
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Converts FFmpeg's open-ended range requests to the bounded ranges YouTube accepts. */
internal object StreamForwarder {
    private val entries = ConcurrentHashMap<String, MediaSource.Http>()
    private val proxy: Proxy? = runCatching {
        val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return@runCatching null
        val uri = URI(raw)
        Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, if (uri.port >= 0) uri.port else 80))
    }.getOrNull()
    private val server by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { task -> Thread(task, "midas-stream-forwarder").apply { isDaemon = true } }
            createContext("/") { exchange ->
                val entry = entries[exchange.requestURI.path.removePrefix("/")]
                if (entry == null || exchange.requestMethod !in setOf("GET", "HEAD")) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                } else {
                    var connection: HttpURLConnection? = null
                    try {
                        val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(exchange.requestHeaders.getFirst("Range") ?: "bytes=0-")
                            ?: throw IllegalArgumentException("Unsupported range")
                        var offset = range.groupValues[1].toLong()
                        val requestedEnd = range.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE
                        val start = offset
                        fun openChunk(from: Long, to: Long): HttpURLConnection {
                            val url = URI(entry.url).toURL()
                            return ((if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection).apply {
                                connectTimeout = 15_000
                                readTimeout = 30_000
                                entry.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                                setRequestProperty("Range", "bytes=$from-$to")
                            }
                        }
                        connection = openChunk(offset, minOf(requestedEnd, offset + CHUNK - 1))
                        if (connection.responseCode != 206) throw IllegalStateException("Upstream rejected range")
                        val total = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: throw IllegalStateException("Missing stream length")
                        val end = minOf(requestedEnd, total - 1)
                        if (start >= total || end < start) { exchange.sendResponseHeaders(416, -1); return@createContext }
                        connection.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
                        exchange.responseHeaders.set("Accept-Ranges", "bytes")
                        exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$total")
                        exchange.responseHeaders.set("Content-Length", (end - start + 1).toString())
                        exchange.sendResponseHeaders(206, if (exchange.requestMethod == "HEAD") -1 else end - start + 1)
                        if (exchange.requestMethod != "HEAD") exchange.responseBody.use { output ->
                            while (offset <= end) {
                                val chunkEnd = minOf(end, offset + CHUNK - 1)
                                connection!!.inputStream.use { input -> input.copyTo(output) }
                                connection!!.disconnect()
                                offset = chunkEnd + 1
                                if (offset <= end) {
                                    connection = openChunk(offset, minOf(end, offset + CHUNK - 1))
                                    if (connection!!.responseCode != 206) throw IllegalStateException("Upstream rejected range")
                                }
                            }
                        }
                    } catch (_: Exception) {
                        runCatching { exchange.sendResponseHeaders(502, -1) }
                    } finally {
                        connection?.disconnect()
                        exchange.close()
                    }
                }
            }
            start()
        }
    }

    fun wrap(source: MediaSource.Http): MediaSource.Http {
        val id = UUID.randomUUID().toString()
        entries[id] = source
        return MediaSource.Http("http://127.0.0.1:${server.address.port}/$id")
    }
    fun release(source: MediaSource.Http) {
        val uri = runCatching { URI(source.url) }.getOrNull() ?: return
        if (uri.host == "127.0.0.1" && uri.port == server.address.port) entries.remove(uri.path.removePrefix("/"))
    }
    private const val CHUNK = 1_048_576L
}
