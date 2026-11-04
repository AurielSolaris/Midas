package app.auriel.midas.ffmpeg

import app.auriel.midas.core.MediaSource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Set MIDAS_PGS_FILE to a video with PGS / VobSub / DVB subtitles to run. */
class BitmapSubtitleTest {
    @Test
    fun decodesPictureSubtitles() {
        val path = System.getenv("MIDAS_PGS_FILE") ?: return
        val engine = FfmpegEngine()
        engine.open(MediaSource.File(path))
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && engine.embeddedSubtitles.value.values.sumOf { it.cues.size } < 2) Thread.sleep(50)
        println("tracks: ${engine.tracks.value.subtitle}")
        val cues = engine.embeddedSubtitles.value.values.flatMap { it.cues }
        println("cues: " + cues.joinToString { "%.2f-%.2f %dx%d@%d,%d on %dx%d".format(it.start, it.end, it.bitmap?.width, it.bitmap?.height, it.bitmap?.x, it.bitmap?.y, it.bitmap?.canvasWidth, it.bitmap?.canvasHeight) })
        val b = cues.firstNotNullOfOrNull { it.bitmap }
        assertTrue(b != null, "picture cue decoded")
        assertTrue(cues.all { it.end > it.start && it.end - it.start < 30 }, "sane times")
        val opaque = (0 until b.pixels.size / 4).count { b.pixels[it * 4 + 3] != 0.toByte() }
        assertTrue(opaque > 50, "has visible pixels: $opaque")
        System.getenv("MIDAS_PGS_PNG")?.let { out ->
            val img = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until b.height) for (x in 0 until b.width) {
                val i = (y * b.width + x) * 4
                val a = b.pixels[i + 3].toInt() and 0xFF
                fun un(v: Byte) = if (a == 0) 0 else ((v.toInt() and 0xFF) * 255 / a).coerceAtMost(255)
                img.setRGB(x, y, (a shl 24) or (un(b.pixels[i + 2]) shl 16) or (un(b.pixels[i + 1]) shl 8) or un(b.pixels[i]))
            }
            ImageIO.write(img, "png", File(out))
        }
        engine.close()
    }
}
