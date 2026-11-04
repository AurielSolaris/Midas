package app.auriel.midas.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Which window corner a web hangs in. */
private enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * Cobwebs in quiet corners plus one small dangling spider. Draw-only: there are no pointer
 * modifiers here, so every click and scroll goes straight through to the app underneath.
 * Thin, low-contrast strokes keep text and thumbnails readable even where a web overlaps them.
 */
@Composable
fun HalloweenDecorations(modifier: Modifier = Modifier, compact: Boolean = false) {
    val thread = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val spiderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val eyeColor = MaterialTheme.colorScheme.primary

    if (compact) {
        // Phones: no rail to hang the spider in and little spare space, so one small corner web.
        Box(modifier.fillMaxSize()) {
            Web(Corner.TopEnd, 96.dp, thread, Modifier.align(Alignment.TopEnd))
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        // Bottom of the navigation rail is always empty, so it gets the biggest web.
        Web(Corner.BottomStart, 220.dp, thread, Modifier.align(Alignment.BottomStart))
        Web(Corner.TopEnd, 150.dp, thread, Modifier.align(Alignment.TopEnd))
        Web(Corner.BottomEnd, 110.dp, thread, Modifier.align(Alignment.BottomEnd))
        DanglingSpider(thread, spiderColor, eyeColor, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Web(corner: Corner, size: Dp, color: Color, modifier: Modifier) {
    Canvas(modifier.size(size)) { drawCornerWeb(corner, color) }
}

/** A quarter orb-web: spokes fanning from the corner, joined by sagging spiral threads. */
private fun DrawScope.drawCornerWeb(corner: Corner, color: Color) {
    val (origin, startAngle) = when (corner) {
        Corner.TopStart -> Offset(0f, 0f) to 0.0
        Corner.TopEnd -> Offset(size.width, 0f) to PI / 2
        Corner.BottomEnd -> Offset(size.width, size.height) to PI
        Corner.BottomStart -> Offset(0f, size.height) to PI * 1.5
    }
    val radius = size.minDimension
    val spokes = 7
    val rings = 6
    val stroke = 1.dp.toPx()

    fun point(spoke: Int, r: Float): Offset {
        val a = startAngle + (PI / 2) * spoke / (spokes - 1)
        return Offset(origin.x + (cos(a) * r).toFloat(), origin.y + (sin(a) * r).toFloat())
    }

    for (s in 0 until spokes) {
        drawLine(color, origin, point(s, radius), strokeWidth = stroke, cap = StrokeCap.Round)
    }
    for (ring in 1..rings) {
        // Uneven spacing looks hand-spun rather than machine-drawn.
        val r = radius * (ring / rings.toFloat()) * (0.92f + 0.08f * (ring % 2))
        val path = Path()
        for (s in 0 until spokes - 1) {
            val a = point(s, r)
            val b = point(s + 1, r)
            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            // Threads sag toward the corner, like silk under its own weight.
            val sag = Offset(mid.x + (origin.x - mid.x) * 0.18f, mid.y + (origin.y - mid.y) * 0.18f)
            if (s == 0) path.moveTo(a.x, a.y)
            path.quadraticTo(sag.x, sag.y, b.x, b.y)
        }
        drawPath(path, color, style = Stroke(width = stroke))
    }
}

/** A small spider on a silk thread, bobbing slowly. */
@Composable
private fun DanglingSpider(thread: Color, body: Color, eyes: Color, modifier: Modifier) {
    val bob by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    // Hangs from the top-right web, below the toolbar and clear of the library's cards.
    Canvas(modifier.size(width = 100.dp, height = 320.dp)) {
        val x = 70.dp.toPx()
        val drop = 150.dp.toPx() + bob * 24.dp.toPx()
        drawLine(thread, Offset(x, 0f), Offset(x, drop), strokeWidth = 1.dp.toPx())
        drawSpider(Offset(x, drop + 7.dp.toPx()), body, eyes)
    }
}

private fun DrawScope.drawSpider(center: Offset, body: Color, eyes: Color) {
    val r = 6.dp.toPx()
    val leg = 1.4.dp.toPx()
    // Four legs per side, each bent at a knee.
    for (side in listOf(-1f, 1f)) {
        for (i in 0 until 4) {
            val spread = (i - 1.5f) * 0.55f
            val knee = Offset(center.x + side * r * 1.6f, center.y + r * (spread * 1.4f - 0.6f))
            val foot = Offset(center.x + side * r * 2.6f, center.y + r * (spread * 2.2f + 0.4f))
            drawLine(body, center, knee, strokeWidth = leg, cap = StrokeCap.Round)
            drawLine(body, knee, foot, strokeWidth = leg, cap = StrokeCap.Round)
        }
    }
    drawCircle(body, radius = r, center = center)
    drawCircle(body, radius = r * 0.6f, center = Offset(center.x, center.y - r * 1.2f))
    drawCircle(eyes, radius = r * 0.16f, center = Offset(center.x - r * 0.22f, center.y - r * 1.3f))
    drawCircle(eyes, radius = r * 0.16f, center = Offset(center.x + r * 0.22f, center.y - r * 1.3f))
}
