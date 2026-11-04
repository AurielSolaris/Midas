package app.auriel.midas.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Loads the FFmpeg natives once. The first load unpacks them from the jar, which takes a moment. */
internal object FfmpegNatives {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        av_log_set_level(AV_LOG_ERROR)
        loaded = true
    }
}

internal fun errorText(code: Int): String {
    val buf = ByteArray(256)
    av_strerror(code, buf, buf.size.toLong())
    val end = buf.indexOf(0).let { if (it < 0) buf.size else it }
    return String(buf, 0, end, Charsets.UTF_8).ifBlank { "error $code" }
}

internal fun AVDictionary?.string(key: String): String? =
    this?.let { av_dict_get(it, key, null, 0) }?.value()?.getString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

internal fun AVRational.toDouble(): Double = if (den() == 0) 0.0 else num().toDouble() / den()

internal fun Long.seconds(timeBase: Double): Double = if (this == AV_NOPTS_VALUE) Double.NaN else this * timeBase

/** Items flowing from the demuxer to a decoder thread. [serial] changes on every seek, so stale items are dropped. */
internal sealed class QueueItem(val serial: Int) {
    class Packet(serial: Int, val packet: AVPacket) : QueueItem(serial)

    /** Start of a new segment after a seek: decoders flush, and skip output before [discardBefore]. */
    class Flush(serial: Int, val discardBefore: Double, val reopenStream: Int? = null) : QueueItem(serial)

    class End(serial: Int) : QueueItem(serial)
}

/** Packet queue with back-pressure. Freed packets are owned by whoever takes them out. */
internal class PacketQueue(private val softLimit: Int) {
    private val deque = LinkedBlockingDeque<QueueItem>()

    val size: Int get() = deque.size
    val isFull: Boolean get() = deque.size >= softLimit

    fun put(item: QueueItem) = deque.putLast(item)

    fun poll(timeoutMs: Long): QueueItem? = deque.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)

    fun clear() {
        while (true) {
            val item = deque.pollFirst() ?: break
            if (item is QueueItem.Packet) av_packet_free(item.packet)
        }
    }
}
