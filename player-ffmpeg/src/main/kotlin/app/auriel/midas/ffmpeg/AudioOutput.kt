package app.auriel.midas.ffmpeg

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * 48 kHz stereo 16-bit output through JavaSound, and the audio clock that playback syncs to.
 *
 * The clock counts frames we've written minus what's still waiting in the line's buffer, so it doesn't
 * depend on how each OS reports `framePosition`. [anchorPts] is the media time of the first frame
 * written after the last flush; one output frame is `speed / 48000` seconds of media.
 */
internal class AudioOutput {
    val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply {
        // ~120 ms: short enough that seeks and pauses feel immediate.
        open(format, SAMPLE_RATE * BYTES_PER_FRAME * 120 / 1000)
    }

    @Volatile var written = 0L
        private set
    @Volatile var anchorPts = Double.NaN
        private set
    @Volatile private var anchorFrames = 0L
    @Volatile private var anchorSpeed = 1.0

    val isAnchored: Boolean get() = !anchorPts.isNaN()

    /** Frames written but not yet played. */
    fun bufferedFrames(): Long = ((line.bufferSize - line.available()) / BYTES_PER_FRAME).toLong()

    fun freeBytes(): Int = line.available()

    /** Media time being heard now (before the user's audio delay). */
    fun position(): Double {
        val played = (written - bufferedFrames() - anchorFrames).coerceAtLeast(0)
        return anchorPts + played.toDouble() / SAMPLE_RATE * anchorSpeed
    }

    fun anchor(pts: Double, speed: Double) {
        anchorFrames = written
        anchorSpeed = speed
        anchorPts = pts
    }

    /** Writes whole frames; returns bytes written (never blocks for long: callers check [freeBytes]). */
    fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        val n = line.write(bytes, offset, length - length % BYTES_PER_FRAME)
        written += n / BYTES_PER_FRAME
        return n
    }

    fun start() = line.start()
    fun stop() = line.stop()

    fun flush() {
        line.flush()
        anchorPts = Double.NaN
    }

    fun close() = runCatching {
        line.stop()
        line.flush()
        line.close()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 4
    }
}
