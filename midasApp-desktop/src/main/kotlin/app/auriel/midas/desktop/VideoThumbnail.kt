package app.auriel.midas.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.auriel.midas.core.MediaSource
import app.auriel.midas.data.CodecMode
import app.auriel.midas.ffmpeg.FfmpegEngine
import app.auriel.midas.ui.player.PlayerIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

private val thumbnailLock = Mutex()

@Composable
internal fun VideoThumbnail(path: String, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { loadThumbnail(path)?.toComposeImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(PlayerIcons.Movie, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private suspend fun loadThumbnail(path: String): BufferedImage? = thumbnailLock.withLock {
    val file = File(path)
    if (!file.isFile) return@withLock null
    val key = MessageDigest.getInstance("SHA-256").digest("${file.absolutePath}:${file.length()}:${file.lastModified()}".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val cache = File(AppDirs.config, "thumbnails/$key.jpg")
    runCatching { ImageIO.read(cache) }.getOrNull()?.let { return@withLock it }
    val engine = FfmpegEngine()
    try {
        engine.decoding = CodecMode.Software
        engine.volume = 0f
        engine.open(MediaSource.File(file.absolutePath))
        engine.play()
        engine.currentFrame()
        var frame = engine.screenshot()
        repeat(100) {
            if (frame == null) { delay(100); engine.currentFrame(); frame = engine.screenshot() }
        }
        val f = frame ?: return@withLock null
        val source = BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val i = y * f.rowBytes + x * 4
            val b = f.pixels[i].toInt() and 255
            val g = f.pixels[i + 1].toInt() and 255
            val r = f.pixels[i + 2].toInt() and 255
            source.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        val width = 320
        val height = (width / f.displayAspect).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try { graphics.drawImage(source, 0, 0, width, height, null) } finally { graphics.dispose() }
        runCatching { cache.parentFile.mkdirs(); ImageIO.write(scaled, "jpg", cache) }
        scaled
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally { engine.close() }
}
