package app.auriel.midas.ffmpeg

import app.auriel.midas.core.MediaSource
import app.auriel.midas.core.PlaybackState
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plays a real file. Set MIDAS_TEST_FILE to a video to run it; skipped otherwise. */
class EngineSmokeTest {
    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    @Test
    fun playsSeeksAndReadsTracks() {
        val path = System.getenv("MIDAS_TEST_FILE") ?: return
        assertTrue(File(path).exists())
        val engine = FfmpegEngine()
        engine.preferredAudioLanguage = "ja"
        engine.open(MediaSource.File(path))
        assertTrue(waitFor(15_000) { engine.state.value == PlaybackState.Ready || engine.state.value is PlaybackState.Error }, "never ready")
        assertEquals(PlaybackState.Ready, engine.state.value, "state: ${engine.state.value}")
        val tracks = engine.tracks.value
        println("tracks: $tracks duration=${engine.duration.value} decoder=${engine.videoDecoder.value}")
        assertEquals(2, tracks.audio.size)
        assertEquals("2", engine.selectedAudio.value, "Japanese audio picked")
        assertEquals(2, tracks.chapters.size)

        assertTrue(waitFor(5_000) { engine.currentFrame() != null }, "first frame")
        engine.play()
        var frames = 0
        var last: Double = -1.0
        val start = System.nanoTime()
        while (System.nanoTime() - start < 3_000_000_000L) {
            val f = engine.currentFrame()
            if (f != null && f.pts != last) { frames++; last = f.pts }
            Thread.sleep(8)
        }
        val t = engine.clock.now()
        println("after 3 s wall: clock=$t frames=$frames lastPts=$last decoder=${engine.videoDecoder.value}")
        assertTrue(t in 2.5..3.5, "clock advanced ~3s: $t")
        assertTrue(frames > 60, "frames shown: $frames")
        assertTrue(abs(last - t) < 0.1, "video in sync with clock: frame $last vs clock $t")

        val subs = engine.embeddedSubtitles.value
        println("subs: " + subs.mapValues { it.value.cues.map { c -> "${c.start}-${c.end} ${c.text}" } })
        assertNotNull(subs["3"]?.activeAt(2.0)?.firstOrNull { it.text == "Hello from Midas" })

        // Seek and check sync afterwards.
        engine.seek(6.0, exact = true)
        val until = System.currentTimeMillis() + 1800
        while (System.currentTimeMillis() < until) { engine.currentFrame(); Thread.sleep(8) }
        val t2 = engine.clock.now()
        val f2 = engine.currentFrame()!!
        println("after seek: clock=$t2 frame=${f2.pts}")
        assertTrue(t2 in 6.8..8.0, "clock after seek: $t2")
        assertTrue(abs(f2.pts - t2) < 0.1, "sync after seek")

        // Switch audio, pause, frame step.
        engine.selectAudio("1")
        repeat(60) { engine.currentFrame(); Thread.sleep(8) }
        engine.pause()
        val paused = engine.clock.now()
        Thread.sleep(300)
        assertTrue(abs(engine.clock.now() - paused) < 0.02, "clock stops when paused")
        repeat(40) { engine.currentFrame(); Thread.sleep(8) }
        val before = engine.currentFrame()!!.pts
        engine.frameStep(true)
        val after = engine.currentFrame()!!.pts
        println("frame step: $before -> $after")
        assertTrue(after > before && after - before < 0.1, "steps forward by a frame or two")

        // Play to the end.
        engine.play()
        assertTrue(waitFor(6_000) { engine.state.value == PlaybackState.Ended }, "ended, clock=${engine.clock.now()}")
        engine.close()
    }

    @Test
    fun decodesInSoftwareToo() {
        val path = System.getenv("MIDAS_TEST_FILE") ?: return
        val engine = FfmpegEngine()
        engine.decoding = app.auriel.midas.data.CodecMode.Software
        engine.open(MediaSource.File(path))
        assertTrue(waitFor(15_000) { engine.currentFrame() != null }, "first frame")
        assertEquals("Software", engine.videoDecoder.value)
        engine.close()
    }

    @Test
    fun exportsClipsWithHardwareAndSoftwareEncoders() {
        val path = System.getenv("MIDAS_TEST_FILE") ?: return
        for (mode in app.auriel.midas.data.CodecMode.entries) {
            val out = File.createTempFile("midas-clip-", ".mp4")
            val r = ClipExporter.export(MediaSource.File(path), 2.0, 5.0, out, mode, audioStream = 1)
            println("export $mode: ${r.encoder} audio=${r.audioCopied} size=${out.length()}")
            assertTrue(out.length() > 10_000, "clip written")
            if (mode == app.auriel.midas.data.CodecMode.Software) assertTrue(r.encoder.startsWith("Software"))
            // The clip plays back with the right length.
            val engine = FfmpegEngine()
            engine.open(MediaSource.File(out.path))
            assertTrue(waitFor(10_000) { engine.duration.value != null })
            val d = engine.duration.value!!
            println("clip duration $d")
            assertTrue(d in 2.8..3.3, "clip is ~3 s: $d")
            engine.close()
            out.delete()
        }
    }
}
