package app.auriel.midas.desktop

import app.auriel.midas.core.MediaSource
import app.auriel.midas.core.PlaybackState
import app.auriel.midas.data.CodecMode
import app.auriel.midas.ffmpeg.FfmpegEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubePlaybackTest {
    @Test
    fun suppliedVideoResolvesAndDecodes() = runBlocking {
        val url = System.getenv("MIDAS_YOUTUBE_TEST_URL") ?: return@runBlocking
        val result = YouTubeResolver.resolve(MediaSource.Http(url))
        assertTrue(result.qualities.isNotEmpty())
        val engine = FfmpegEngine()
        try {
            engine.decoding = CodecMode.Software
            engine.volume = 0f
            engine.open(result.qualities.first().source)
            engine.play()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (engine.currentFrame() == null && engine.state.value !is PlaybackState.Error && System.nanoTime() < deadline) {
                Thread.sleep(100)
            }
            assertNotNull(engine.currentFrame(), "No video frame: ${engine.state.value}")
            assertTrue(engine.tracks.value.audio.isNotEmpty(), "The selected stream must contain audio")
        } finally {
            engine.close()
        }
    }
}
