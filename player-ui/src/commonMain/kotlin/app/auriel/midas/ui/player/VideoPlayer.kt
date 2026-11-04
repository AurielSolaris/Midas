package app.auriel.midas.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auriel.midas.core.AssRenderer
import app.auriel.midas.core.AssSupport
import app.auriel.midas.core.Chapter
import app.auriel.midas.core.Languages
import app.auriel.midas.core.MediaSource
import app.auriel.midas.core.MediaTracks
import app.auriel.midas.core.PlaybackEngine
import app.auriel.midas.core.PlaybackState
import app.auriel.midas.core.TrackInfo
import app.auriel.midas.data.MidasSettings
import app.auriel.midas.data.SettingsRepository
import app.auriel.midas.platform.decodeSubtitleText
import app.auriel.midas.platform.unzipEntries
import app.auriel.midas.subtitles.MediaGuess
import app.auriel.midas.subtitles.SubtitleFetcher
import app.auriel.midas.subtitles.SubtitleParser
import app.auriel.midas.subtitles.SubtitleTrack
import app.auriel.midas.ui.LocalFullscreen
import app.auriel.midas.ui.theme.MediaTheme
import app.auriel.midas.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

/** What to play. [key] identifies it for resume positions and the library (a path or URL). */
class PlayerItem(
    val key: String,
    val source: MediaSource,
    val title: String,
    val detail: String?,
    val guess: MediaGuess,
    /** Subtitle files found next to the video, best match first. */
    val externalSubtitles: List<SubtitleTrack> = emptyList(),
)

/** A subtitle choice: one of the file's own tracks, or a loaded file. */
private sealed interface SubChoice {
    val key: String
    val label: String

    data class Embedded(val info: TrackInfo) : SubChoice {
        override val key get() = "e:${info.id}"
        override val label get() = info.label + if (info.isForced) " · Forced" else ""
    }

    class External(val track: SubtitleTrack, val n: Int) : SubChoice {
        override val key get() = "x:$n"
        override val label get() = track.label
    }
}

/**
 * Full-window video player with Midas's own controls and subtitle renderer.
 *
 * Keys: Space/K play · ←/→ 5 s · J/L 10 s · ↑/↓ or wheel volume · M mute · F fullscreen · C subtitles on/off ·
 * V next subtitle track · T next audio track · Z/X subtitle delay · G/H audio delay · -/= subtitle size ·
 * [/] speed · , . frame step · S screenshot · A A–B loop · E export the A–B clip · W fill/fit · 0–9 jump to 0–90 % ·
 * N/P next/previous file · Home/End · Esc.
 */
@Composable
fun VideoPlayer(
    engine: PlaybackEngine,
    item: PlayerItem,
    settings: SettingsRepository,
    fetcher: SubtitleFetcher,
    hasPrevious: Boolean,
    hasNext: Boolean,
    nextTitle: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onPickSubtitleFolder: (() -> Unit)? = null,
    statusLine: String? = null,
    onExportClip: ClipExport? = null,
    qualityOptions: List<String> = emptyList(),
    selectedQuality: String? = null,
    onQuality: (String) -> Unit = {},
    resumeMinDuration: Double = 120.0,
    resumeMinPosition: Double = 30.0,
) = MediaTheme {
    PlayerContent(engine, item, settings, fetcher, hasPrevious, hasNext, nextTitle, onPrevious, onNext, onClose, onPickSubtitleFolder, statusLine, onExportClip, qualityOptions, selectedQuality, onQuality, resumeMinDuration, resumeMinPosition)
}

/** Saves [start, end] of the current video as a clip; reports 0..1 progress and returns a message to show. */
typealias ClipExport = suspend (start: Double, end: Double, onProgress: (Float) -> Unit) -> String

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerContent(
    engine: PlaybackEngine,
    item: PlayerItem,
    settings: SettingsRepository,
    fetcher: SubtitleFetcher,
    hasPrevious: Boolean,
    hasNext: Boolean,
    nextTitle: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onPickSubtitleFolder: (() -> Unit)?,
    statusLine: String?,
    onExportClip: ClipExport?,
    qualityOptions: List<String>,
    selectedQuality: String?,
    onQuality: (String) -> Unit,
    resumeMinDuration: Double,
    resumeMinPosition: Double,
) {
    val fullscreen = LocalFullscreen.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    val state by engine.state.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val durationOrNull by engine.duration.collectAsState()
    val duration = durationOrNull ?: 0.0
    val tracks by engine.tracks.collectAsState()
    val selectedAudio by engine.selectedAudio.collectAsState()
    val embeddedSubs by engine.embeddedSubtitles.collectAsState()
    val prefs by settings.settings.collectAsState()

    // ---- Open ----
    var resumedFrom by remember(item.key) { mutableStateOf<Double?>(null) }
    LaunchedEffect(item.key) {
        engine.volume = settings.current.playerVolume
        val saved = settings.current.resumePositions[item.key]?.takeIf { it > 5 }
        engine.open(item.source, saved ?: 0.0)
        engine.play()
        if (saved != null) {
            resumedFrom = saved
            delay(6_000)
            resumedFrom = null
        }
    }

    // Smooth clock for subtitles and the seek bar.
    var now by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(engine) {
        while (true) {
            withFrameNanos { }
            now = engine.clock.now()
        }
    }

    fun seekTo(seconds: Double, exact: Boolean = true) {
        if (duration <= 0) return
        engine.seek(seconds.coerceIn(0.0, duration), exact)
    }

    // Save the position now and then (and on close), so long videos resume where they were left.
    fun saveResume() {
        val t = engine.clock.now()
        val d = duration
        val keep = d > resumeMinDuration && t > resumeMinPosition && t < d - resumeMinPosition
        settings.saveResume(item.key, if (keep) t else null)
    }
    LaunchedEffect(item.key) {
        while (true) {
            delay(15_000)
            if (engine.isPlaying.value) saveResume()
        }
    }
    DisposableEffect(item.key) {
        onDispose {
            saveResume()
            settings.update { it.copy(playerVolume = engine.volume) }
        }
    }

    // ---- On-screen feedback & controls visibility ----
    var osd by remember { mutableStateOf<String?>(null) }
    var osdTick by remember { mutableIntStateOf(0) }
    fun flash(text: String) { osd = text; osdTick++ }
    LaunchedEffect(osdTick) { if (osd != null) { delay(1100); osd = null } }

    // ---- Subtitles ----
    var loaded by remember(item.key) { mutableStateOf(item.externalSubtitles) }
    val choices by remember(item.key) {
        derivedStateOf {
            tracks.subtitle.map { SubChoice.Embedded(it) } +
                loaded.mapIndexed { i, t -> SubChoice.External(t, i) }
        }
    }
    var activeKey by remember(item.key) { mutableStateOf<String?>(null) }
    var lastActiveKey by remember(item.key) { mutableStateOf<String?>(null) }
    var autoPicked by remember(item.key) { mutableStateOf(false) }
    val active = choices.firstOrNull { it.key == activeKey }
    val activeTrack: SubtitleTrack? = when (active) {
        is SubChoice.Embedded -> embeddedSubs[active.info.id]
        is SubChoice.External -> active.track
        null -> null
    }
    var subtitleDelay by remember(item.key) { mutableDoubleStateOf(0.0) }
    var subtitleScale by remember { mutableFloatStateOf(settings.current.subtitleScale) }
    var showFinder by remember { mutableStateOf(false) }
    val cues = activeTrack?.activeAt(now - subtitleDelay).orEmpty()
    val subtitleStyle = prefs.subtitleStyle
    var showStyle by remember { mutableStateOf(false) }

    // ASS/SSA tracks draw through libass with their own styling when the engine has it.
    val assSupport = engine as? AssSupport
    LaunchedEffect(assSupport, subtitleScale, subtitleStyle) { assSupport?.setAssStyle(subtitleScale, subtitleStyle) }
    val assKey = active?.key?.takeIf { assSupport?.assAvailable == true }
    val assRenderer: AssRenderer? = remember(assKey, tracks, state) {
        when (val a = active) {
            is SubChoice.Embedded -> if (a.info.codec == "ass" || a.info.codec == "ssa") assSupport?.embeddedAssRenderer(a.info.id) else null
            is SubChoice.External -> a.track.assScript?.let { assSupport?.assScriptRenderer(it) }
            null -> null
        }
    }
    DisposableEffect(assRenderer) { onDispose { assRenderer?.close() } }

    fun selectSub(choice: SubChoice?, announce: Boolean = true) {
        activeKey = choice?.key
        if (choice != null) lastActiveKey = choice.key
        engine.selectSubtitle((choice as? SubChoice.Embedded)?.info?.id)
        if (announce) flash(if (choice == null) "Subtitles off" else "Subtitles: ${choice.label}")
    }

    // Language preferences pick a track once the file's tracks are known (e.g. Japanese audio → English subs).
    LaunchedEffect(item.key, tracks, selectedAudio) {
        if (autoPicked || state != PlaybackState.Ready && tracks == MediaTracks()) return@LaunchedEffect
        if (tracks == MediaTracks()) return@LaunchedEffect
        autoPicked = true
        val audioLang = tracks.audio.firstOrNull { it.id == selectedAudio }?.language
        autoPickSubtitle(choices, audioLang, prefs)?.let { selectSub(it, announce = false) }
    }

    fun addTrack(track: SubtitleTrack) {
        loaded = loaded + track
        selectSub(SubChoice.External(track, loaded.size - 1))
    }
    val pickFile = rememberSubtitleFilePicker { name, bytes ->
        try {
            val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            val (fileName, content) = if (isZip) {
                unzipEntries(bytes).firstOrNull { SubtitleParser.fileExtensions.contains(it.first.substringAfterLast('.').lowercase()) }
                    ?: error("no subtitle files in the archive")
            } else name to bytes
            addTrack(SubtitleParser.parse(decodeSubtitleText(content), fileName.substringBeforeLast('.'), fileName = fileName))
        } catch (e: Exception) {
            flash("Couldn't read $name: ${e.message}")
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    fun showControls() { controlsVisible = true; poke++ }
    LaunchedEffect(poke, isPlaying, menuOpen, showFinder) {
        if (isPlaying && !menuOpen && !showFinder) {
            delay(3_000)
            controlsVisible = false
        }
    }

    fun togglePlay() {
        if (isPlaying) engine.pause() else engine.play()
        showControls()
    }
    fun skip(by: Double) { seekTo(now + by); flash(if (by > 0) "+${by.toInt()} s" else "${by.toInt()} s") }
    var volume by remember { mutableFloatStateOf(settings.current.playerVolume) }
    var volumeBeforeMute by remember { mutableFloatStateOf(1f) }
    fun setVolume(v: Float, announce: Boolean = true) {
        volume = v.coerceIn(0f, 1f)
        engine.volume = volume
        if (announce) flash("Volume ${(volume * 100).roundToInt()} %")
    }
    fun toggleMute() {
        if (volume > 0f) { volumeBeforeMute = volume; setVolume(0f, false); flash("Muted") }
        else setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 1f)
    }
    var speed by remember { mutableFloatStateOf(1f) }
    fun setSpeed(s: Float) { speed = s; engine.speed = s; flash("Speed ${speedLabel(s)}") }
    var audioDelay by remember(item.key) { mutableDoubleStateOf(0.0) }
    fun setAudioDelay(d: Double) { audioDelay = (d * 10).roundToInt() / 10.0; engine.audioDelay = audioDelay; flash("Audio delay ${signed(audioDelay)} s") }
    var loop by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(false) }

    fun toggleSubtitles() {
        if (active != null) selectSub(null)
        else (choices.firstOrNull { it.key == lastActiveKey } ?: choices.firstOrNull())?.let { selectSub(it) }
            ?: run { showFinder = true }
    }
    fun cycleSubtitles() {
        if (choices.isEmpty()) { flash("No subtitle tracks"); return }
        val i = choices.indexOfFirst { it.key == activeKey }
        selectSub(if (i + 1 >= choices.size) null else choices[i + 1])
    }
    fun cycleAudio() {
        val audio = tracks.audio
        if (audio.size < 2) { flash(if (audio.isEmpty()) "No audio" else "Only one audio track"); return }
        val i = audio.indexOfFirst { it.id == selectedAudio }
        val next = audio[(i + 1) % audio.size]
        engine.selectAudio(next.id)
        flash("Audio: ${next.label}")
    }
    fun screenshot() {
        val frame = engine.screenshot() ?: run { flash("No frame to save yet"); return }
        scope.launch {
            val path = withContext(Dispatchers.Default) { runCatching { saveScreenshot(frame, item.title) } }
            flash(path.fold({ "Screenshot saved: ${it.substringAfterLast('/').substringAfterLast('\\')}" }, { "Screenshot failed: ${it.message}" }))
        }
    }

    // ---- A–B loop ----
    var loopA by remember(item.key) { mutableStateOf<Double?>(null) }
    var loopB by remember(item.key) { mutableStateOf<Double?>(null) }
    fun abLoop() {
        when {
            loopA == null -> { loopA = now; flash("Loop start ${formatTime(now)} (A again for the end)") }
            loopB == null && now > loopA!! + 0.2 -> { loopB = now; flash("Looping ${formatTime(loopA!!)} – ${formatTime(now)}") }
            else -> { loopA = null; loopB = null; flash("A–B loop off") }
        }
    }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    fun exportClip() {
        val export = onExportClip ?: return
        val a = loopA
        val b = loopB
        if (a == null || b == null) { flash("Set an A–B loop first: press A at the start and again at the end"); return }
        if (exportProgress != null) { flash("Already exporting"); return }
        exportProgress = 0f
        scope.launch {
            val message = try {
                export(a, b) { p -> exportProgress = p }
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            exportProgress = null
            flash(message)
        }
    }
    LaunchedEffect(loopA, loopB) {
        val a = loopA ?: return@LaunchedEffect
        val b = loopB ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (engine.clock.now() >= b) engine.seek(a, exact = true)
        }
    }

    // ---- End of file: loop, next-episode countdown, or stop ----
    var countdown by remember(item.key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(state, item.key) {
        if (state != PlaybackState.Ended) { countdown = null; return@LaunchedEffect }
        settings.saveResume(item.key, null)
        when {
            loop -> { engine.seek(0.0, exact = true); engine.play() }
            hasNext && prefs.autoplayNext -> {
                for (s in 5 downTo 1) { countdown = s; delay(1_000) }
                countdown = null
                onNext()
            }
            else -> showControls()
        }
    }

    // ---- Chapters ----
    val chapters = tracks.chapters
    val chapter = chapters.lastOrNull { now >= it.start && now < it.end }
    val skippable = chapter?.takeIf { it.skipKind != null && it.end - now > 3 }

    fun close() {
        fullscreen.set(false)
        onClose()
    }

    KeepScreenOn(isPlaying)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.K, Key.MediaPlayPause -> togglePlay()
                    Key.DirectionLeft -> skip(if (e.isShiftPressed) -1.0 else -5.0)
                    Key.DirectionRight -> skip(if (e.isShiftPressed) 1.0 else 5.0)
                    Key.J -> skip(-10.0)
                    Key.L -> skip(10.0)
                    Key.DirectionUp -> setVolume(volume + 0.05f)
                    Key.DirectionDown -> setVolume(volume - 0.05f)
                    Key.M -> toggleMute()
                    Key.F -> fullscreen.toggle()
                    Key.C -> toggleSubtitles()
                    Key.V -> cycleSubtitles()
                    Key.T -> cycleAudio()
                    Key.Z -> { subtitleDelay -= 0.1; flash("Subtitle delay ${signed(subtitleDelay)} s") }
                    Key.X -> { subtitleDelay += 0.1; flash("Subtitle delay ${signed(subtitleDelay)} s") }
                    Key.G -> setAudioDelay(audioDelay - 0.1)
                    Key.H -> setAudioDelay(audioDelay + 0.1)
                    Key.Minus -> { subtitleScale = (subtitleScale - 0.1f).coerceAtLeast(0.5f); flash("Subtitle size ${(subtitleScale * 100).roundToInt()} %") }
                    Key.Equals, Key.Plus -> { subtitleScale = (subtitleScale + 0.1f).coerceAtMost(2.5f); flash("Subtitle size ${(subtitleScale * 100).roundToInt()} %") }
                    Key.LeftBracket -> SPEEDS.lastOrNull { it < speed - 0.01f }?.let(::setSpeed)
                    Key.RightBracket -> SPEEDS.firstOrNull { it > speed + 0.01f }?.let(::setSpeed)
                    Key.Comma -> { engine.frameStep(false); flash("◀ Frame") }
                    Key.Period -> { engine.frameStep(true); flash("Frame ▶") }
                    Key.S -> screenshot()
                    Key.A -> abLoop()
                    Key.E -> exportClip()
                    Key.W -> { fill = !fill; flash(if (fill) "Fill screen" else "Fit to screen") }
                    Key.MoveHome -> seekTo(0.0)
                    Key.MoveEnd -> seekTo(duration - 1)
                    Key.N, Key.MediaNext -> if (hasNext) onNext()
                    Key.P, Key.MediaPrevious -> if (hasPrevious) onPrevious()
                    Key.Escape -> if (fullscreen.isFullscreen) fullscreen.set(false) else close()
                    Key.Backspace -> close()
                    else -> {
                        val digit = digitOf(e.key) ?: return@onPreviewKeyEvent false
                        seekTo(duration * digit / 10)
                    }
                }
                showControls()
                true
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move -> showControls()
                            PointerEventType.Scroll -> {
                                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (dy != 0f) setVolume(volume - dy * 0.05f)
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        togglePlay()
                        runCatching { focus.requestFocus() }
                    },
                    onDoubleTap = { fullscreen.toggle() },
                )
            },
    ) {
        VideoSurface(
            engine, fill, cues.mapNotNull { it.bitmap },
            ass = assRenderer,
            assTime = { engine.clock.now() - subtitleDelay },
            modifier = Modifier.fillMaxSize(),
        )

        // Subtitles sit above the controls while they're showing.
        val subtitleLift by animateDpAsState(if (controlsVisible) 112.dp else 28.dp)
        val textCues = if (assRenderer != null) emptyList() else cues.filter { it.bitmap == null }
        StyledSubtitleText(textCues.filter { !it.top }.map { it.text }, subtitleStyle, subtitleScale, Modifier.align(Alignment.BottomCenter).padding(bottom = subtitleLift))
        StyledSubtitleText(textCues.filter { it.top }.map { it.text }, subtitleStyle, subtitleScale, Modifier.align(Alignment.TopCenter).padding(top = if (controlsVisible) 72.dp else 24.dp))

        if (state == PlaybackState.Loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp))
        }

        (state as? PlaybackState.Error)?.let { error ->
            PlaybackError(error.message, hasNext, onNext, ::close, Modifier.align(Alignment.Center))
        }

        osd?.let {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
            ) { Text(it, Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s), style = MaterialTheme.typography.titleMedium) }
        }

        resumedFrom?.let { from ->
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = Spacing.m, bottom = 128.dp),
            ) {
                Row(Modifier.padding(start = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Text("Resumed at ${formatTime(from)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
                    TextButton(onClick = { seekTo(0.0); resumedFrom = null }) {
                        Text("Start over", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }

        skippable?.let { c ->
            ExtendedFloatingActionButton(
                onClick = { seekTo(c.end + 0.05); flash(c.skipKind!!.label) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.l, bottom = 128.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(PlayerIcons.SkipNext, null)
                Spacer(Modifier.width(Spacing.s))
                Text(c.skipKind!!.label)
            }
        }

        exportProgress?.let { p ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = Spacing.m).width(240.dp),
            ) {
                Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("Exporting clip… ${(p * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        countdown?.let { s ->
            NextUpCard(nextTitle, s, onPlayNow = { countdown = null; onNext() }, onCancel = { countdown = null }, Modifier.align(Alignment.BottomEnd).padding(Spacing.l).padding(bottom = 104.dp))
        }

        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(horizontal = Spacing.s, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    PlayerIconButton(Icons.Filled.Close, "Close (Esc)", onClick = ::close)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = listOfNotNull(item.detail, chapter?.title, statusLine).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (onExportClip != null && loopA != null && loopB != null) {
                        TextButton(onClick = ::exportClip, enabled = exportProgress == null) {
                            Icon(PlayerIcons.Movie, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.s))
                            Text("Export clip (E)")
                        }
                    }
                    PlayerIconButton(PlayerIcons.Camera, "Screenshot (S)", onClick = ::screenshot)
                }

                if (!isPlaying && state == PlaybackState.Ready || state == PlaybackState.Ended && countdown == null) {
                    LargeFloatingActionButton(
                        onClick = ::togglePlay,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.Center),
                    ) { Icon(if (state == PlaybackState.Ended) PlayerIcons.Replay else PlayerIcons.Play, "Play", Modifier.size(36.dp)) }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = Spacing.s)
                        .padding(top = Spacing.l, bottom = Spacing.xs),
                ) {
                    SeekBar(
                        position = now,
                        duration = duration,
                        chapters = chapters,
                        loopA = loopA,
                        loopB = loopB,
                        onScrub = { seekTo(it, exact = false) },
                        onSeek = { seekTo(it, exact = true) },
                        onInteract = ::showControls,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (hasPrevious) PlayerIconButton(PlayerIcons.SkipPrevious, "Previous (P)", onClick = onPrevious)
                        PlayerIconButton(if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play, if (isPlaying) "Pause (Space)" else "Play (Space)", onClick = ::togglePlay)
                        if (hasNext) PlayerIconButton(PlayerIcons.SkipNext, "Next (N)", onClick = onNext)
                        PlayerIconButton(PlayerIcons.Replay, "Back 10 seconds (J)") { skip(-10.0) }
                        PlayerIconButton(PlayerIcons.Replay, "Forward 10 seconds (L)", Modifier.scale(scaleX = -1f, scaleY = 1f)) { skip(10.0) }
                        PlayerIconButton(if (volume == 0f) PlayerIcons.VolumeOff else PlayerIcons.VolumeUp, "Mute (M)", onClick = ::toggleMute)
                        Slider(
                            value = volume,
                            onValueChange = { setVolume(it, announce = false); showControls() },
                            colors = playerSliderColors(),
                            modifier = Modifier.width(96.dp),
                        )
                        Text(
                            "${formatTime(now)} / ${formatTime(duration)}",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = Spacing.s),
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        PlayerSettingsMenu(speed, ::setSpeed, qualityOptions, selectedQuality, onQuality, onOpenChange = { menuOpen = it })
                        PlayerIconButton(PlayerIcons.Repeat, if (loop) "Loop: on" else "Loop: off", selected = loop) {
                            loop = !loop
                            flash(if (loop) "Loop on" else "Loop off")
                        }
                        AudioMenu(
                            tracks = tracks.audio,
                            selected = selectedAudio,
                            delaySeconds = audioDelay,
                            onSelect = { engine.selectAudio(it.id); flash("Audio: ${it.label}") },
                            onDelay = ::setAudioDelay,
                            onOpenChange = { menuOpen = it },
                        )
                        SubtitleMenu(
                            choices = choices,
                            active = active,
                            delaySeconds = subtitleDelay,
                            scale = subtitleScale,
                            onSelect = { selectSub(it) },
                            onDelay = { subtitleDelay = it },
                            onScale = { subtitleScale = it; settings.update { s -> s.copy(subtitleScale = it) } },
                            onFind = { showFinder = true },
                            onLoadFile = pickFile,
                            onLoadFolder = onPickSubtitleFolder,
                            onStyle = { showStyle = true },
                            onOpenChange = { menuOpen = it },
                        )
                        PlayerIconButton(
                            if (fullscreen.isFullscreen) PlayerIcons.FullscreenExit else PlayerIcons.Fullscreen,
                            if (fullscreen.isFullscreen) "Exit fullscreen (F)" else "Fullscreen (F)",
                        ) { fullscreen.toggle() }
                    }
                }
            }
        }
    }

    if (showStyle) {
        SubtitleStyleDialog(
            style = subtitleStyle,
            scale = subtitleScale,
            onStyle = { st -> settings.update { it.copy(subtitleStyle = st) } },
            onScale = { sc -> subtitleScale = sc; settings.update { it.copy(subtitleScale = sc) } },
            onDismiss = { showStyle = false; runCatching { focus.requestFocus() } },
        )
    }

    if (showFinder) {
        SubtitleFinderDialog(
            fetcher = fetcher,
            guess = item.guess,
            language = prefs.subtitleLanguage,
            onLoaded = { addTrack(it); showFinder = false },
            onDismiss = { showFinder = false; runCatching { focus.requestFocus() } },
        )
    }
}

/**
 * Which subtitles to turn on. When the audio isn't in the preferred subtitle language: a full track in
 * that language (a loaded file first, then the file's own). When it is: a forced track (signs only), if any.
 */
private fun autoPickSubtitle(choices: List<SubChoice>, audioLanguage: String?, prefs: MidasSettings): SubChoice? {
    if (!prefs.autoSubtitles) return null
    val want = Languages.normalize(prefs.subtitleLanguage) ?: return null
    fun lang(c: SubChoice) = when (c) {
        is SubChoice.Embedded -> Languages.normalize(c.info.language)
        is SubChoice.External -> Languages.normalize(c.track.language)
    }
    fun isSignsOnly(c: SubChoice) = c is SubChoice.Embedded && (c.info.isForced || c.info.title?.contains(Regex("sign|song|forced", RegexOption.IGNORE_CASE)) == true)
    val audio = Languages.normalize(audioLanguage)
    if (audio != null && audio == want) {
        return choices.firstOrNull { it is SubChoice.Embedded && it.info.isForced && lang(it) == want }
    }
    // The file's own full track first (it's always in sync with this release), then a loaded file.
    val external = choices.filterIsInstance<SubChoice.External>()
    val embedded = choices.filterIsInstance<SubChoice.Embedded>().filter { lang(it) == want }
    return embedded.firstOrNull { !isSignsOnly(it) }
        ?: external.firstOrNull { lang(it) == want }
        ?: embedded.firstOrNull()
        ?: external.firstOrNull { lang(it) == null } // a sidecar file without a language tag is almost always what the user wants
}

// =================================================================================================
// Pieces
// =================================================================================================

@Composable
private fun SeekBar(
    position: Double,
    duration: Double,
    chapters: List<Chapter>,
    loopA: Double?,
    loopB: Double?,
    onScrub: (Double) -> Unit,
    onSeek: (Double) -> Unit,
    onInteract: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastScrub by remember { mutableStateOf(0L) }
    val max = duration.toFloat().coerceAtLeast(0.001f)
    val markColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val loopColor = MaterialTheme.colorScheme.tertiary
    Box {
        Slider(
            value = (dragging ?: position.toFloat()).coerceIn(0f, max),
            valueRange = 0f..max,
            enabled = duration > 0,
            onValueChange = {
                dragging = it
                onInteract()
                // Keyframe seeks while dragging, a few times a second, so the picture follows the thumb.
                val nowMs = currentTimeMs()
                if (nowMs - lastScrub > 120) { lastScrub = nowMs; onScrub(it.toDouble()) }
            },
            onValueChangeFinished = { dragging?.let { onSeek(it.toDouble()) }; dragging = null },
            colors = playerSliderColors(),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        // Chapter and A–B marks over the track (the Slider's track is inset by the thumb radius).
        if (duration > 0 && (chapters.size > 1 || loopA != null)) {
            Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                val inset = 10.dp.toPx()
                val width = size.width - inset * 2
                fun x(t: Double) = inset + (t / duration).toFloat().coerceIn(0f, 1f) * width
                val cy = size.height / 2
                for (c in chapters.drop(1)) {
                    drawLine(markColor, Offset(x(c.start), cy - 5.dp.toPx()), Offset(x(c.start), cy + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
                loopA?.let { a ->
                    val b = loopB ?: a
                    drawLine(loopColor, Offset(x(a), cy), Offset(x(b).coerceAtLeast(x(a) + 2f), cy), strokeWidth = 6.dp.toPx())
                }
            }
        }
        dragging?.let {
            val t = it.toDouble()
            val chapterTitle = chapters.lastOrNull { c -> t >= c.start && t < c.end }?.title
            Text(
                listOfNotNull(formatTime(t), chapterTitle).joinToString(" · "),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-28).dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            )
        }
    }
}

private val startMark = kotlin.time.TimeSource.Monotonic.markNow()
private fun currentTimeMs(): Long = startMark.elapsedNow().inWholeMilliseconds

@Composable
private fun NextUpCard(title: String?, seconds: Int, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.widthIn(max = 360.dp)) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text("Up next in $seconds s", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { (5 - seconds + 1) / 5f }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = onPlayNow) { Text("Play now") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PlayerSettingsMenu(speed: Float, onSpeed: (Float) -> Unit, qualities: List<String>, selectedQuality: String?, onQuality: (String) -> Unit, onOpenChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    fun close() { open = false; page = 0; onOpenChange(false) }
    Box {
        PlayerIconButton(Icons.Filled.Settings, "Playback settings") { open = true; onOpenChange(true) }
        DropdownMenu(expanded = open, onDismissRequest = ::close, modifier = Modifier.widthIn(min = 220.dp, max = 320.dp)) {
            if (page == 0) {
                DropdownMenuItem(text = { Text("Playback speed   ${speedLabel(speed)}") }, onClick = { page = 1 })
                DropdownMenuItem(text = { Text("Resolution   ${selectedQuality ?: "Original"}") }, onClick = { page = 2 }, enabled = qualities.isNotEmpty())
            } else if (page == 1) {
                DropdownMenuItem(text = { Text("‹  Playback speed") }, onClick = { page = 0 })
                SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(if (s == 1f) "Normal" else speedLabel(s)) },
                    leadingIcon = { if (abs(s - speed) < 0.01f) Icon(Icons.Filled.Check, null) },
                    onClick = { onSpeed(s); close() },
                )
                }
            } else {
                DropdownMenuItem(text = { Text("‹  Resolution") }, onClick = { page = 0 })
                qualities.forEach { quality ->
                    DropdownMenuItem(text = { Text(quality) }, leadingIcon = { if (quality == selectedQuality) Icon(Icons.Filled.Check, null) }, onClick = { onQuality(quality); close() })
                }
            }
        }
    }
}

@Composable
private fun AudioMenu(
    tracks: List<TrackInfo>,
    selected: String?,
    delaySeconds: Double,
    onSelect: (TrackInfo) -> Unit,
    onDelay: (Double) -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun close() { open = false; onOpenChange(false) }
    Box {
        PlayerIconButton(PlayerIcons.Audiotrack, "Audio track (T)") { open = true; onOpenChange(true) }
        DropdownMenu(expanded = open, onDismissRequest = ::close, modifier = Modifier.widthIn(min = 260.dp, max = 400.dp)) {
            Text("Audio", Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs), style = MaterialTheme.typography.labelMedium)
            if (tracks.isEmpty()) DropdownMenuItem(text = { Text("No audio") }, onClick = {}, enabled = false)
            tracks.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { if (t.id == selected) Icon(Icons.Filled.Check, null) },
                    onClick = { onSelect(t); close() },
                )
            }
            HorizontalDivider()
            StepperRow("Delay", "${signed(delaySeconds)} s  (G/H)", { onDelay(delaySeconds - 0.1) }, { onDelay(delaySeconds + 0.1) })
        }
    }
}

@Composable
private fun SubtitleMenu(
    choices: List<SubChoice>,
    active: SubChoice?,
    delaySeconds: Double,
    scale: Float,
    onSelect: (SubChoice?) -> Unit,
    onDelay: (Double) -> Unit,
    onScale: (Float) -> Unit,
    onFind: () -> Unit,
    onLoadFile: () -> Unit,
    onLoadFolder: (() -> Unit)?,
    onStyle: () -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun close() { open = false; onOpenChange(false) }
    Box {
        PlayerIconButton(PlayerIcons.ClosedCaption, "Subtitles (C, V)", selected = active != null) { open = true; onOpenChange(true) }
        DropdownMenu(expanded = open, onDismissRequest = ::close, modifier = Modifier.widthIn(min = 280.dp, max = 420.dp)) {
            DropdownMenuItem(
                text = { Text("Off") },
                leadingIcon = { if (active == null) Icon(Icons.Filled.Check, null) },
                onClick = { onSelect(null); close() },
            )
            choices.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    c is SubChoice.External -> "Loaded file"
                                    c is SubChoice.Embedded && !c.info.isText -> "In the file · picture subtitles"
                                    else -> "In the file"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { if (c.key == active?.key) Icon(Icons.Filled.Check, null) },
                    onClick = { onSelect(c); close() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Find subtitles online…") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, onClick = { close(); onFind() })
            DropdownMenuItem(text = { Text("Load from file…") }, leadingIcon = { Icon(PlayerIcons.Subtitles, null) }, onClick = { close(); onLoadFile() })
            DropdownMenuItem(text = { Text("Style: font, colors, box…") }, leadingIcon = { Icon(PlayerIcons.Palette, null) }, onClick = { close(); onStyle() })
            if (onLoadFolder != null) {
                DropdownMenuItem(
                    text = { Text("Use a subtitle folder…") },
                    leadingIcon = { Icon(PlayerIcons.Folder, null) },
                    onClick = { close(); onLoadFolder() },
                )
            }
            HorizontalDivider()
            StepperRow("Delay", "${signed(delaySeconds)} s  (Z/X)", { onDelay(delaySeconds - 0.1) }, { onDelay(delaySeconds + 0.1) })
            StepperRow("Size", "${(scale * 100).roundToInt()} %", { onScale((scale - 0.1f).coerceAtLeast(0.5f)) }, { onScale((scale + 0.1f).coerceAtMost(2.5f)) })
        }
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.padding(horizontal = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onMinus) { Icon(PlayerIcons.Remove, "Decrease ${label.lowercase()}") }
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        IconButton(onClick = onPlus) { Icon(PlayerIcons.Add, "Increase ${label.lowercase()}") }
    }
}

@Composable
private fun PlaybackError(message: String, hasNext: Boolean, onNext: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.padding(Spacing.l)) {
        Column(
            Modifier.padding(Spacing.l).widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Can't play this file", style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (hasNext) FilledTonalButton(onClick = onNext) { Text("Play next") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** Standard MD3 icon button; with [selected] it's a toggle icon button (primary when on). */
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    if (selected == null) {
        IconButton(onClick = onClick, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(icon, contentDescription = description, modifier = modifier)
        }
    } else {
        IconToggleButton(
            checked = selected,
            onCheckedChange = { onClick() },
            colors = IconButtonDefaults.iconToggleButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                checkedContentColor = MaterialTheme.colorScheme.primary,
            ),
        ) { Icon(icon, contentDescription = description, modifier = modifier) }
    }
}

@Composable
private fun playerSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
)

fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

private fun speedLabel(s: Float): String =
    (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

private fun signed(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return if (r > 0) "+$r" else r.toString()
}

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}
