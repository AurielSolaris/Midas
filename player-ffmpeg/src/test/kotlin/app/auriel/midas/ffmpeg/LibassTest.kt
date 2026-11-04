package app.auriel.midas.ffmpeg

import app.auriel.midas.core.MediaSource
import app.auriel.midas.core.PlaybackState
import app.auriel.midas.data.SubtitleStyle
import app.auriel.midas.subtitles.SubtitleBitmap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Set MIDAS_ASS_FILE to a video with an ASS track (MIDAS_ASS_OUT: folder for rendered PNGs). */
class LibassTest {
    @Test
    fun rendersEmbeddedAssWithStyles() {
        val path = System.getenv("MIDAS_ASS_FILE") ?: return
        System.setProperty("midas.libass.dir", File("../midasApp-desktop/appResources/windows-x64/libass").absolutePath)
        assertTrue(Libass.available, "libass loads")
        println("libass version: %x".format(Libass.api!!.ass_library_version()))
        val engine = FfmpegEngine()
        engine.open(MediaSource.File(path))
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && engine.state.value != PlaybackState.Ready) Thread.sleep(20)
        Thread.sleep(800) // demuxer reads ahead, feeding the events
        val id = engine.tracks.value.subtitle.first { it.codec == "ass" }.id
        val renderer = assertNotNull(engine.embeddedAssRenderer(id), "embedded renderer")
        val out = System.getenv("MIDAS_ASS_OUT")

        val styled = assertNotNull(renderer.render(2.0, 1280, 720), "rendered at 2 s")
        println("styled: ${styled.width}x${styled.height} at ${styled.x},${styled.y}")
        assertTrue(styled.width > 600, "spans the dialogue and the sign")
        out?.let { save(styled, File(it, "ass-styled.png")) }

        engine.setAssStyle(1.3f, SubtitleStyle(fontFamily = "Georgia", textColor = 0xFFFFD54F, backgroundColor = 0xCC000000, overrideAss = true))
        val overridden = assertNotNull(renderer.render(2.0, 1280, 720))
        out?.let { save(overridden, File(it, "ass-override.png")) }

        val script = assertNotNull(engine.assScriptRenderer(File(path).resolveSibling("styled.ass").readText()))
        assertNotNull(script.render(2.0, 1920, 1080), "script renderer")
        script.close()
        engine.close()
    }

    private fun save(b: SubtitleBitmap, file: File) {
        val img = BufferedImage(b.canvasWidth, b.canvasHeight, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(40, 90, 160), b.canvasWidth.toFloat(), b.canvasHeight.toFloat(), java.awt.Color(30, 120, 60))
        g.fillRect(0, 0, b.canvasWidth, b.canvasHeight)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val i = (y * b.width + x) * 4
            val a = b.pixels[i + 3].toInt() and 0xFF
            if (a == 0) continue
            val bg = img.getRGB(b.x + x, b.y + y)
            fun ch(v: Byte, shift: Int) = ((v.toInt() and 0xFF) + (bg shr shift and 0xFF) * (255 - a) / 255).coerceAtMost(255)
            img.setRGB(b.x + x, b.y + y, (ch(b.pixels[i + 2], 16) shl 16) or (ch(b.pixels[i + 1], 8) shl 8) or ch(b.pixels[i], 0))
        }
        ImageIO.write(img, "png", file)
    }
}
