package app.auriel.midas.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A warm gold glow from the top corner and a few slowly twinkling four-point sparkles.
 * Draw-only (no pointer handling) and low-contrast, so content stays readable on top.
 */
@Composable
fun GoldDecorations(modifier: Modifier = Modifier) {
    val gold = MaterialTheme.colorScheme.primary
    val green = MaterialTheme.colorScheme.secondary
    val phase by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(modifier.fillMaxSize()) {
        val r = size.minDimension * 0.9f
        drawCircle(
            Brush.radialGradient(listOf(gold.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width, 0f), radius = r),
            radius = r,
            center = Offset(size.width, 0f),
        )
        drawCircle(
            Brush.radialGradient(listOf(green.copy(alpha = 0.07f), Color.Transparent), center = Offset(0f, size.height), radius = r),
            radius = r,
            center = Offset(0f, size.height),
        )
        // Fixed spots, each twinkling on its own phase. Kept to the edges, away from content.
        for ((i, spot) in SPARKLES.withIndex()) {
            val t = ((phase + i * 0.17f) % 1f)
            val glow = (sin(t * 2 * PI).toFloat() * 0.5f + 0.5f)
            val c = Offset(size.width * spot.x, size.height * spot.y)
            drawSparkle(c, (6 + 6 * glow).dp.toPx() * spot.scale, gold.copy(alpha = 0.18f + 0.32f * glow))
        }
    }
}

private class Spot(val x: Float, val y: Float, val scale: Float)

private val SPARKLES = listOf(
    Spot(0.94f, 0.17f, 1.2f), Spot(0.86f, 0.23f, 0.7f), Spot(0.96f, 0.31f, 0.5f),
    Spot(0.04f, 0.92f, 0.9f), Spot(0.12f, 0.96f, 0.6f), Spot(0.97f, 0.9f, 0.6f),
)

private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val waist = radius * 0.22f
    val p = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + waist, center.y - waist, center.x + radius, center.y)
        quadraticTo(center.x + waist, center.y + waist, center.x, center.y + radius)
        quadraticTo(center.x - waist, center.y + waist, center.x - radius, center.y)
        quadraticTo(center.x - waist, center.y - waist, center.x, center.y - radius)
        close()
    }
    drawPath(p, color)
}

/** The decoration for the current theme, if it has one. */
@Composable
fun ThemeDecorations(modifier: Modifier = Modifier) {
    when (LocalAppTheme.current) {
        AppTheme.Halloween -> HalloweenDecorations(modifier)
        AppTheme.Gold -> GoldDecorations(modifier)
        else -> Unit
    }
}
