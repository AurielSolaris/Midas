package app.auriel.midas.ffmpeg

import app.auriel.midas.core.MediaSource
import app.auriel.midas.core.PlaybackState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Set MIDAS_REAL_FILE to a long real video with an embedded text subtitle track. */
class RealFileTest {
    private fun waitFor(ms: Long, pull: FfmpegEngine? = null, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { pull?.currentFrame(); if (cond()) return true; Thread.sleep(8) }
        return cond()
    }

    @Test
    fun embeddedSubtitlesAndSyncOnARealEpisode() {
        val path = System.getenv("MIDAS_REAL_FILE") ?: return
        val engine = FfmpegEngine()
        val opened = System.nanoTime()
        engine.open(MediaSource.File(path))
        assertTrue(waitFor(15_000) { engine.state.value == PlaybackState.Ready })
        assertTrue(waitFor(10_000, engine) { engine.currentFrame() != null })
        println("first frame after ${(System.nanoTime() - opened) / 1_000_000} ms, decoder=${engine.videoDecoder.value}")
        val sub = engine.tracks.value.subtitle.first { it.isText }
        println("subtitle track: ${sub.label}")

        fun checkAround(t: Double, label: String) {
            engine.seek(t, exact = true)
            engine.play()
            var frames = 0
            var last = -1.0
            val start = System.nanoTime()
            while (System.nanoTime() - start < 4_000_000_000L) {
                val f = engine.currentFrame()
                if (f != null && f.pts != last) { frames++; last = f.pts }
                Thread.sleep(4)
            }
            val now = engine.clock.now()
            val track = engine.embeddedSubtitles.value[sub.id]
            val near = track?.cues?.filter { it.start >= t - 1 && it.start <= now + 20 }.orEmpty()
            println("$label: clock=%.2f frame=%.3f fps=%.1f cues near=%d total=%d".format(now, last, frames / 4.0, near.size, track?.cues?.size ?: 0))
            near.take(3).forEach { println("   %.2f-%.2f %s".format(it.start, it.end, it.text.replace("\n", " / "))) }
            assertTrue(abs(last - now) < 0.1, "$label: video in sync")
            assertTrue(frames > 80, "$label: smooth playback ($frames frames in 4 s)")
            assertTrue(near.isNotEmpty(), "$label: embedded subtitle cues read around $t s")
        }
        checkAround(120.0, "at 2:00")
        checkAround(700.0, "at 11:40")
        engine.close()
    }
}
