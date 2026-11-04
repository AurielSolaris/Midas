package app.auriel.midas.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auriel.midas.data.SubtitleStyle
import app.auriel.midas.ui.theme.Spacing
import kotlin.math.roundToInt

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFFFFD54F, 0xFF80DEEA, 0xFFA5D6A7, 0xFFF48FB1, 0xFFFFAB91, 0xFFBDBDBD)
private val OUTLINE_COLORS = listOf(0xFF000000, 0xFF263238, 0xFF1A237E, 0xFF4A148C, 0xFF3E2723, 0xFFFFFFFF)
private val BOX_COLORS = listOf(0x00000000, 0xFF000000, 0xFF212121, 0xFF0D47A1, 0xFF1B5E20, 0xFF4A148C, 0xFFFFFFFF)

/**
 * The user's subtitle look: font face, size, bold, text and outline colors, outline thickness, and a
 * background box with its opacity, plus whether the same look replaces ASS scripts' own styles.
 */
@Composable
fun SubtitleStyleEditor(
    style: SubtitleStyle,
    scale: Float,
    fonts: List<String>,
    onStyle: (SubtitleStyle) -> Unit,
    onScale: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SubtitlePreview(style, scale, Modifier.fillMaxWidth().height(150.dp))

        Label("Font")
        FontPicker(style.fontFamily, fonts) { onStyle(style.copy(fontFamily = it)) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Size  ${(scale * 100).roundToInt()} %", Modifier.width(120.dp))
            Slider(value = scale, onValueChange = { onScale((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2.5f, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Bold", Modifier.weight(1f))
            Switch(checked = style.bold, onCheckedChange = { onStyle(style.copy(bold = it)) })
        }

        Label("Text color")
        ColorRow(TEXT_COLORS, style.textColor) { onStyle(style.copy(textColor = it)) }

        Label("Outline")
        ColorRow(OUTLINE_COLORS, style.outlineColor) { onStyle(style.copy(outlineColor = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(if (style.outlineWidth <= 0.01f) "Thickness  none" else "Thickness  ${"%.1f".format(style.outlineWidth)}", Modifier.width(140.dp))
            Slider(value = style.outlineWidth, onValueChange = { onStyle(style.copy(outlineWidth = (it * 10).roundToInt() / 10f)) }, valueRange = 0f..3f, modifier = Modifier.weight(1f))
        }

        Label("Background box")
        val boxRgb = style.backgroundColor and 0xFFFFFF
        val boxAlpha = ((style.backgroundColor ushr 24) and 0xFF) / 255f
        ColorRow(BOX_COLORS, if (boxAlpha == 0f) 0x00000000 else (0xFF000000 or boxRgb)) { c ->
            val keepAlpha = if (boxAlpha == 0f) 0.6f else boxAlpha
            onStyle(style.copy(backgroundColor = if (c ushr 24 == 0L) 0L else withAlpha(c, keepAlpha)))
        }
        if (boxAlpha > 0f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Opacity  ${(boxAlpha * 100).roundToInt()} %", Modifier.width(140.dp))
                Slider(value = boxAlpha, onValueChange = { onStyle(style.copy(backgroundColor = withAlpha(0xFF000000 or boxRgb, it.coerceAtLeast(0.05f)))) }, valueRange = 0.05f..1f, modifier = Modifier.weight(1f))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Use this style for ASS subtitles too")
                Text(
                    "ASS/SSA subtitles normally keep their own fonts, colors and positions (fansub typesetting). Size always applies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = style.overrideAss, onCheckedChange = { onStyle(style.copy(overrideAss = it)) })
        }
    }
}

/** The editor as a dialog, for the player's subtitle menu. */
@Composable
fun SubtitleStyleDialog(
    style: SubtitleStyle,
    scale: Float,
    onStyle: (SubtitleStyle) -> Unit,
    onScale: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val fonts = remember { systemFontFamilies() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitle style") },
        text = {
            SubtitleStyleEditor(style, scale, fonts, onStyle, onScale, Modifier.widthIn(min = 420.dp, max = 520.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { onStyle(SubtitleStyle()); onScale(1f) }) { Text("Reset") }
        },
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun FontPicker(current: String, fonts: List<String>, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    Column {
        Surface(
            onClick = { open = !open },
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s + Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current.ifBlank { "Default (sans-serif)" },
                    fontFamily = fontFamilyOf(current),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (open) {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Search ${fonts.size} fonts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
            )
            val shown = remember(query, fonts) { listOf("") + fonts.filter { query.isBlank() || it.contains(query, ignoreCase = true) } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(shown, key = { it }) { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(name); open = false; query = "" }.padding(horizontal = Spacing.m, vertical = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(28.dp)) { if (name == current) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                        Text(name.ifBlank { "Default (sans-serif)" }, fontFamily = fontFamilyOf(name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, onPick: (Long) -> Unit) {
    var hex by remember(selected) { mutableStateOf(if (selected ushr 24 == 0L) "" else "#%06X".format(selected and 0xFFFFFF)) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { c ->
            val none = c ushr 24 == 0L
            val isSelected = (c and 0xFFFFFF) == (selected and 0xFFFFFF) && none == (selected ushr 24 == 0L)
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (none) MaterialTheme.colorScheme.surfaceVariant else Color(c.toInt()))
                    .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (none) Text("∅", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            hex,
            { v ->
                hex = v
                Regex("^#?([0-9a-fA-F]{6})$").find(v.trim())?.let { onPick(0xFF000000 or it.groupValues[1].toLong(16)) }
            },
            placeholder = { Text("#RRGGBB") },
            singleLine = true,
            modifier = Modifier.width(120.dp),
        )
    }
}

private fun withAlpha(argb: Long, alpha: Float): Long = ((alpha * 255).roundToInt().toLong() shl 24) or (argb and 0xFFFFFF)

/** Sample lines over a picture-like background, drawn exactly like the player draws subtitles. */
@Composable
private fun SubtitlePreview(style: SubtitleStyle, scale: Float, modifier: Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(Color(0xFF3A6EA5), Color(0xFFE0C068), Color(0xFF2E7D4F)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        StyledSubtitleText(listOf("Subtitles will look like this.", "Second line, for reading two at once."), style, scale, Modifier.padding(bottom = Spacing.m), referenceWidthDp = 900f)
    }
}

/**
 * Subtitle lines in the user's style: their font, colors, outline and optional box. Size follows the
 * picture width ([referenceWidthDp] lets the preview look like a full-size player).
 */
@Composable
fun StyledSubtitleText(lines: List<String>, style: SubtitleStyle, scale: Float, modifier: Modifier = Modifier, referenceWidthDp: Float? = null) {
    if (lines.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth().then(modifier), contentAlignment = Alignment.Center) {
        val width = referenceWidthDp?.let { minOf(it, maxWidth.value * 1.6f) } ?: maxWidth.value
        val size = (width * 0.028f).coerceIn(14f, 44f) * scale * (if (referenceWidthDp != null) maxWidth.value / width else 1f).coerceAtMost(1f)
        val family: FontFamily = fontFamilyOf(style.fontFamily)
        val base = TextStyle(
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
            fontWeight = if (style.bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = family,
            textAlign = TextAlign.Center,
        )
        val box = style.backgroundColor ushr 24 != 0L
        val lineMax = maxWidth * 0.9f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // With a box each line gets its own, like TV captions; without, one block.
            val blocks = if (box) lines.flatMap { it.split('\n') } else listOf(lines.joinToString("\n"))
            blocks.forEach { text ->
                Box(
                    Modifier
                        .widthIn(max = lineMax)
                        .then(if (box) Modifier.background(Color(style.backgroundColor.toInt()), MaterialTheme.shapes.extraSmall).padding(horizontal = (size * 0.35f).dp, vertical = (size * 0.06f).dp) else Modifier.padding(horizontal = Spacing.m)),
                ) {
                    if (style.outlineWidth > 0.01f) {
                        Text(text, color = Color(style.outlineColor.toInt()), style = base.copy(drawStyle = Stroke(width = size / 6f * style.outlineWidth, join = StrokeJoin.Round)))
                    }
                    Text(
                        text,
                        color = Color(style.textColor.toInt()),
                        style = if (box) base else base.copy(shadow = Shadow(Color(style.outlineColor.toInt()).copy(alpha = 0.6f), blurRadius = size / 4f)),
                    )
                }
            }
        }
    }
}
