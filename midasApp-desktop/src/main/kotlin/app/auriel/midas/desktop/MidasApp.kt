package app.auriel.midas.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.midas.core.PlaybackState
import app.auriel.midas.data.CodecMode
import app.auriel.midas.data.MidasSettings
import app.auriel.midas.ffmpeg.ClipExporter
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.auriel.midas.data.RecentItem
import app.auriel.midas.data.SavedPlaylist
import app.auriel.midas.data.SettingsRepository
import app.auriel.midas.ffmpeg.FfmpegEngine
import app.auriel.midas.subtitles.SubtitleFetcher
import app.auriel.midas.ui.player.PlayerIcons
import app.auriel.midas.ui.player.PlayerItem
import app.auriel.midas.ui.player.VideoPlayer
import app.auriel.midas.ui.player.formatTime
import app.auriel.midas.ui.theme.AppTheme
import app.auriel.midas.ui.theme.Spacing
import app.auriel.midas.ui.theme.ThemeDecorations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppDependencies(
    val settings: SettingsRepository,
    val engine: FfmpegEngine,
    val fetcher: SubtitleFetcher,
    val state: AppState,
    /** Native file pickers (they need the window). */
    val pickFile: () -> File?,
    val pickFolder: (title: String) -> File?,
)

@Composable
fun MidasApp(deps: AppDependencies) {
    val state = deps.state
    when (state.screen) {
        Screen.Player -> PlayerHost(deps)
        Screen.Settings -> SettingsScreen(deps.settings, onBack = { state.screen = Screen.Library })
        Screen.Folder -> FolderScreen(deps)
        Screen.Library -> LibraryScreen(deps)
    }
}

// =================================================================================================
// Player
// =================================================================================================

@Composable
private fun PlayerHost(deps: AppDependencies) {
    val state = deps.state
    val source = state.current ?: run { state.screen = Screen.Library; return }
    val prefs by deps.settings.settings.collectAsState()
    var item by remember { mutableStateOf<PlayerItem?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var qualities by remember { mutableStateOf<List<YouTubeResolver.Quality>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf<String?>(null) }
    DisposableEffect(qualities) {
        val registered = qualities
        onDispose { registered.forEach { StreamForwarder.release(it.source) } }
    }
    LaunchedEffect(source, state.reloadTick) {
        item = null
        loadError = null
        qualities = emptyList()
        selectedQuality = null
        deps.engine.decoding = prefs.videoDecoding
        deps.engine.preferredAudioLanguage = prefs.audioLanguage.ifBlank { null }
        val result = try {
            withContext(Dispatchers.IO) {
                val original = state.buildItem(source)
                if (YouTubeResolver.isYouTube(source)) {
                    val resolved = YouTubeResolver.resolve(source as app.auriel.midas.core.MediaSource.Http)
                    PlayerItem(original.key, resolved.qualities.first().source, resolved.title, source.url, original.guess) to resolved.qualities
                } else original to emptyList()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't open this video."
            return@LaunchedEffect
        }
        val (built, availableQualities) = result
        qualities = availableQualities
        selectedQuality = availableQualities.firstOrNull()?.label
        item = built
        deps.settings.addRecent(
            RecentItem(built.key, built.title, System.currentTimeMillis(), deps.settings.current.recent.firstOrNull { it.location == built.key }?.duration),
        )
    }
    // Remember durations for the library's progress bars.
    val duration by deps.engine.duration.collectAsState()
    val engineState by deps.engine.state.collectAsState()
    LaunchedEffect(duration, item?.key) {
        val d = duration ?: return@LaunchedEffect
        val key = item?.key ?: return@LaunchedEffect
        deps.settings.update { s -> s.copy(recent = s.recent.map { if (it.location == key) it.copy(duration = d) else it }) }
    }
    val decoder by deps.engine.videoDecoder.collectAsState()

    val current = item
    if (current == null || current.key != state.keyOf(source)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            val error = loadError
            if (error == null) CircularProgressIndicator()
            else Column(Modifier.widthIn(max = 520.dp).padding(Spacing.l), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(error)
                Button(onClick = { deps.engine.close(); state.screen = if (state.returnToFolder) Screen.Folder else Screen.Library }) { Text("Back") }
            }
        }
        return
    }
    val nextTitle = state.playlist.getOrNull(state.index + 1)?.displayName?.substringBeforeLast('.')
    VideoPlayer(
        engine = deps.engine,
        item = current,
        settings = deps.settings,
        fetcher = deps.fetcher,
        hasPrevious = state.hasPrevious,
        hasNext = state.hasNext,
        nextTitle = nextTitle,
        onPrevious = state::previous,
        onNext = state::next,
        onClose = {
            deps.engine.pause()
            deps.engine.close()
            state.screen = if (state.returnToFolder) Screen.Folder else Screen.Library
        },
        onPickSubtitleFolder = (current.source as? app.auriel.midas.core.MediaSource.File)?.let { src ->
            {
                val dir = File(src.path).parentFile
                deps.pickFolder("Folder with this show's subtitles")?.let { chosen ->
                    deps.settings.update { s -> s.copy(subtitleFolders = s.subtitleFolders + (dir.absolutePath to chosen.absolutePath)) }
                    state.reloadTick++
                }
            }
        },
        statusLine = decoder?.takeIf { engineState == PlaybackState.Ready || engineState == PlaybackState.Ended },
        qualityOptions = qualities.map { it.label },
        selectedQuality = selectedQuality,
        onQuality = { label ->
            qualities.firstOrNull { it.label == label }?.let { quality ->
                if (selectedQuality != label) {
                    val position = deps.engine.clock.now()
                    val playing = deps.engine.isPlaying.value
                    deps.engine.open(quality.source, position)
                    if (playing) deps.engine.play() else deps.engine.pause()
                    selectedQuality = label
                }
            }
        },
        onExportClip = { start, end, onProgress ->
            val file = clipFile(current.title, start, end)
            val audio = deps.engine.selectedAudio.value?.toIntOrNull() ?: -1
            val mode = deps.settings.current.videoEncoding
            val result = withContext(Dispatchers.IO) {
                ClipExporter.export(current.source, start, end, file, mode, audio) { p -> onProgress(p) }
            }
            "Clip saved: ${result.file.name} · ${result.encoder}" +
                if (audio >= 0 && !result.audioCopied) " · no sound (MP4 can't hold this audio format)" else ""
        },
    )
}

private val UNSAFE_FILE_CHARS = Regex("""[\\/:*?"<>|]""")

/** Videos/Midas/<title> 1.02.03–1.02.10.mp4 */
private fun clipFile(title: String, start: Double, end: Double): File {
    val home = File(System.getProperty("user.home"))
    val videos = listOf(File(home, "Videos"), File(home, "Movies")).firstOrNull { it.isDirectory } ?: home
    val safe = title.replace(UNSAFE_FILE_CHARS, "_").take(80)
    fun t(s: Double) = formatTime(s).replace(':', '.')
    var file = File(File(videos, "Midas"), "$safe ${t(start)}–${t(end)}.mp4")
    var n = 2
    while (file.exists()) file = File(file.parentFile, "$safe ${t(start)}–${t(end)} ($n).mp4").also { n++ }
    return file
}

// =================================================================================================
// Library
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderScreen(deps: AppDependencies) {
    val state = deps.state
    val prefs by deps.settings.settings.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.folderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { state.screen = Screen.Library }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    val path = state.folderPath
                    if (path != null) {
                        val saved = path in prefs.savedFolders
                        TextButton(onClick = { deps.settings.update { s -> s.copy(savedFolders = if (saved) s.savedFolders - path else s.savedFolders + path) } }) {
                            Text(if (saved) "Remove saved folder" else "Save folder")
                        }
                    }
                    val id = state.savedPlaylistId
                    if (id != null) {
                        TextButton(onClick = {
                            deps.pickFile()?.let { file ->
                                deps.settings.update { s -> s.copy(playlists = s.playlists.map { p -> if (p.id == id && file.absolutePath !in p.files) p.copy(files = p.files + file.absolutePath) else p }) }
                                state.openSavedPlaylist(id)
                            }
                        }) { Text("Add video") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (state.folderPlaylist.isEmpty()) item {
                Text("This playlist is empty. Select Add video to add files.", modifier = Modifier.padding(Spacing.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.folderPlaylist.size) { index ->
                val source = state.folderPlaylist[index]
                ListItem(
                    headlineContent = { Text(source.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { VideoThumbnail((source as app.auriel.midas.core.MediaSource.File).path, Modifier.width(112.dp).height(63.dp)) },
                    trailingContent = {
                        val id = state.savedPlaylistId
                        if (id != null) IconButton(onClick = {
                            deps.settings.update { s -> s.copy(playlists = s.playlists.map { p -> if (p.id == id) p.copy(files = p.files.filterIndexed { i, _ -> i != index }) else p }) }
                            state.openSavedPlaylist(id)
                        }) { Icon(Icons.Filled.Close, "Remove from playlist") }
                    },
                    modifier = Modifier.clickable { state.playFolderItem(index) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(deps: AppDependencies) {
    val state = deps.state
    val prefs by deps.settings.settings.collectAsState()
    var showUrl by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); state.message = null }
    }

    fun openFile() { deps.pickFile()?.let(state::open) }
    fun openFolder() { deps.pickFolder("Open a folder of videos")?.let(state::openFolder) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                            MidasMark()
                            Text("Midas", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = {
                        ActionButton(PlayerIcons.Movie, "Open file") { openFile() }
                        ActionButton(PlayerIcons.Folder, "Open folder") { openFolder() }
                        ActionButton(PlayerIcons.Link, "Open link") { showUrl = true }
                        TextButton(onClick = { showCreatePlaylist = true }) { Text("New playlist") }
                        ThemeMenu(AppTheme.fromName(prefs.theme)) { t -> deps.settings.update { it.copy(theme = t.name) } }
                        ActionButton(Icons.Filled.Settings, "Settings") { state.screen = Screen.Settings }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val continueWatching = prefs.recent.filter { r -> prefs.resumePositions[r.location] != null }
            if (prefs.recent.isEmpty() && prefs.savedFolders.isEmpty() && prefs.playlists.isEmpty()) {
                EmptyLibrary(Modifier.padding(padding), ::openFile, ::openFolder) { showUrl = true }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xl),
                ) {
                    if (prefs.savedFolders.isNotEmpty()) {
                        item { SectionTitle("Saved folders") }
                        items(prefs.savedFolders, key = { "folder-$it" }) { path ->
                            ListItem(
                                headlineContent = { Text(File(path).name.ifBlank { path }) },
                                supportingContent = { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(PlayerIcons.Folder, null) },
                                trailingContent = { IconButton(onClick = { deps.settings.update { it.copy(savedFolders = it.savedFolders - path) } }) { Icon(Icons.Filled.Close, "Remove saved folder") } },
                                modifier = Modifier.clickable { state.openFolder(File(path)) },
                            )
                        }
                    }
                    if (prefs.playlists.isNotEmpty()) {
                        item { SectionTitle("Playlists") }
                        items(prefs.playlists, key = { "playlist-${it.id}" }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                supportingContent = { Text("${playlist.files.size} videos") },
                                leadingContent = { Icon(PlayerIcons.Movie, null) },
                                trailingContent = { IconButton(onClick = { deps.settings.update { it.copy(playlists = it.playlists.filterNot { p -> p.id == playlist.id }) } }) { Icon(Icons.Filled.Close, "Delete playlist") } },
                                modifier = Modifier.clickable { state.openSavedPlaylist(playlist.id) },
                            )
                        }
                    }
                    if (continueWatching.isNotEmpty()) {
                        item { SectionTitle("Continue watching") }
                        item {
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.m),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                            ) {
                                items(continueWatching, key = { it.location }) { r ->
                                    ContinueCard(r, prefs.resumePositions[r.location] ?: 0.0) { state.openRecent(r) }
                                }
                            }
                        }
                    }
                    if (prefs.recent.isNotEmpty()) item { SectionTitle("Recent") }
                    items(prefs.recent, key = { "r-" + it.location }) { r ->
                        RecentRow(
                            r,
                            prefs.resumePositions[r.location],
                            onOpen = { state.openRecent(r) },
                            onRemove = {
                                deps.settings.update { s -> s.copy(recent = s.recent - r, resumePositions = s.resumePositions - r.location) }
                            },
                        )
                    }
                }
            }
        }
        ThemeDecorations()
    }

    if (showUrl) {
        OpenUrlDialog(onOpen = { url, headers -> showUrl = false; state.openUrl(url, headers) }, onDismiss = { showUrl = false })
    }
    if (showCreatePlaylist) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                val playlist = SavedPlaylist(java.util.UUID.randomUUID().toString(), name.trim())
                deps.settings.update { it.copy(playlists = it.playlists + playlist) }
                showCreatePlaylist = false
                state.openSavedPlaylist(playlist.id)
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") } },
        )
    }
}

/** The app icon (film frame with a crown), from resources/icon.png. */
@Composable
private fun MidasMark() {
    val icon = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            ?.use { javax.imageio.ImageIO.read(it).toComposeImageBitmap() }
    }
    if (icon != null) {
        androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun ThemeMenu(current: AppTheme, onPick: (AppTheme) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionButton(PlayerIcons.Palette, "Theme") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppTheme.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label, fontWeight = if (t == current) FontWeight.SemiBold else FontWeight.Normal) },
                    leadingIcon = { Swatches(t) },
                    onClick = { onPick(t); open = false },
                )
            }
        }
    }
}

@Composable
private fun Swatches(theme: AppTheme) {
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        theme.swatches.forEach { c ->
            Box(Modifier.size(18.dp).clip(CircleShape).background(c).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onFile: () -> Unit, onFolder: () -> Unit, onUrl: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(PlayerIcons.Play, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("Drop a video here", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Midas plays any format, with every audio and subtitle track inside the file. " +
                    "Open a folder to play its episodes in order.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = onFile) {
                    Icon(PlayerIcons.Movie, null, Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.s)); Text("Open file")
                }
                FilledTonalButton(onClick = onFolder) {
                    Icon(PlayerIcons.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.s)); Text("Open folder")
                }
                OutlinedButton(onClick = onUrl) {
                    Icon(PlayerIcons.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.s)); Text("Open link")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, top = Spacing.l, bottom = Spacing.s),
    )
}

@Composable
private fun ContinueCard(item: RecentItem, position: Double, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.width(280.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (!item.location.startsWith("http")) VideoThumbnail(item.location, Modifier.fillMaxWidth().height(132.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Icon(PlayerIcons.Play, null, tint = MaterialTheme.colorScheme.primary)
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val d = item.duration
            if (d != null && d > 0) {
                LinearProgressIndicator(progress = { (position / d).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (d != null) "${formatTime(position)} of ${formatTime(d)}" else "Stopped at ${formatTime(position)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem, position: Double?, onOpen: () -> Unit, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(item.location, position?.let { "at ${formatTime(it)}" }, timeAgo(item.openedAt)).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { if (item.location.startsWith("http")) Icon(PlayerIcons.Link, null) else VideoThumbnail(item.location, Modifier.width(112.dp).height(63.dp)) },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove from recent") } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

private fun timeAgo(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} d ago"
    }
}

@Composable
private fun OpenUrlDialog(onOpen: (String, Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    val valid = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(url, { url = it }, label = { Text("Video URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    headers, { headers = it },
                    label = { Text("HTTP headers (optional)") },
                    placeholder = { Text("Name: value, one per line") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val h = headers.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
                onOpen(url.trim(), h)
            }) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =================================================================================================
// Settings
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val prefs by settings.settings.collectAsState()
    fun set(transform: (MidasSettings) -> MidasSettings) = settings.update(transform)
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.m),
            ) {
                Column(Modifier.widthIn(max = 720.dp)) {
                    SectionTitle("Theme")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(horizontal = Spacing.m)) {
                        AppTheme.entries.forEach { t ->
                            ThemeCard(t, selected = AppTheme.fromName(prefs.theme) == t) { set { it.copy(theme = t.name) } }
                        }
                    }

                    SectionTitle("Playback")
                    ChoiceRow(
                        "Video decoding",
                        "Hardware uses the graphics card (D3D11VA / VideoToolbox / VAAPI) and switches to software for formats it can't decode. Applies to the next video.",
                        prefs.videoDecoding,
                    ) { v -> set { it.copy(videoDecoding = v) } }
                    ChoiceRow(
                        "Video encoding",
                        "For exported clips (set an A–B loop with A, then press E). Hardware uses NVENC, Quick Sync, AMF, Media Foundation or VideoToolbox, whichever works here; software uses OpenH264.",
                        prefs.videoEncoding,
                    ) { v -> set { it.copy(videoEncoding = v) } }
                    SwitchRow("Play the next file", "At the end of a video in a folder, play the next one after 5 seconds.", prefs.autoplayNext) { v -> set { it.copy(autoplayNext = v) } }
                    TextRow("Preferred audio language", "e.g. ja for Japanese. Blank uses the file's default track.", prefs.audioLanguage) { v -> set { it.copy(audioLanguage = v.trim()) } }

                    SectionTitle("Subtitles")
                    TextRow("Subtitle language", "Two-letter code: en, ja, es…", prefs.subtitleLanguage) { v -> set { it.copy(subtitleLanguage = v.trim()) } }
                    SwitchRow("Turn on subtitles automatically", "When the audio is in another language, show subtitles in yours.", prefs.autoSubtitles) { v -> set { it.copy(autoSubtitles = v) } }
                    SwitchRow("Load subtitle files next to videos", "Same-named .srt/.ass files, and Subs/ folders matched by episode.", prefs.autoLoadSubtitleFiles) { v -> set { it.copy(autoLoadSubtitleFiles = v) } }
                    TextRow("Jimaku API key", "Free from jimaku.cc → Account. Anime subtitles.", prefs.jimakuApiKey, secret = true) { v -> set { it.copy(jimakuApiKey = v.trim()) } }
                    TextRow("OpenSubtitles API key", "Free from opensubtitles.com/consumers.", prefs.openSubtitlesApiKey, secret = true) { v -> set { it.copy(openSubtitlesApiKey = v.trim()) } }
                    TextRow("SubDL API key", "Free from subdl.com → Panel → API.", prefs.subDlApiKey, secret = true) { v -> set { it.copy(subDlApiKey = v.trim()) } }
                    if (prefs.subtitleFolders.isNotEmpty()) {
                        ListItem(
                            headlineContent = { Text("Subtitle folders") },
                            supportingContent = { Text("${prefs.subtitleFolders.size} video folder(s) use a separate subtitle folder") },
                            trailingContent = { TextButton(onClick = { set { it.copy(subtitleFolders = emptyMap()) } }) { Text("Forget") } },
                        )
                    }

                    SectionTitle("Subtitle style")
                    val fonts = remember { app.auriel.midas.ui.player.systemFontFamilies() }
                    app.auriel.midas.ui.player.SubtitleStyleEditor(
                        style = prefs.subtitleStyle,
                        scale = prefs.subtitleScale,
                        fonts = fonts,
                        onStyle = { st -> set { it.copy(subtitleStyle = st) } },
                        onScale = { sc -> set { it.copy(subtitleScale = sc) } },
                        modifier = Modifier.padding(horizontal = Spacing.m),
                    )
                    TextButton(
                        onClick = { set { it.copy(subtitleStyle = app.auriel.midas.data.SubtitleStyle(), subtitleScale = 1f) } },
                        modifier = Modifier.padding(horizontal = Spacing.s),
                    ) { Text("Reset subtitle style") }

                    SectionTitle("Library")
                    ListItem(
                        headlineContent = { Text("Recent files and resume positions") },
                        supportingContent = { Text("${prefs.recent.size} recent, ${prefs.resumePositions.size} positions saved") },
                        trailingContent = { TextButton(onClick = { set { it.copy(recent = emptyList(), resumePositions = emptyMap()) } }) { Text("Clear") } },
                    )

                    SectionTitle("About")
                    Text(
                        "${AppInfo.NAME} ${AppInfo.VERSION}\n" +
                            "Playback by FFmpeg 8.1 (LGPL 2.1, linked dynamically; source at ffmpeg.org) through JavaCPP (Apache 2.0).\n" +
                            "ASS subtitles by libass (ISC) with FreeType, HarfBuzz, FriBidi, Fontconfig and GLib; their licences are in the install folder under libass/licenses.\n" +
                            "Settings: ${AppDirs.config.path}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                }
            }
        }
        ThemeDecorations()
    }
}

@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = theme.swatches
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colors[2],
        border = androidx.compose.foundation.BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(width = 132.dp, height = 96.dp),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(colors[0]))
                Box(Modifier.size(24.dp).clip(CircleShape).background(colors[1]))
            }
            Text(theme.label, style = MaterialTheme.typography.titleSmall, color = if (theme.isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(title: String, detail: String, value: CodecMode, onChange: (CodecMode) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            SingleChoiceSegmentedButtonRow {
                CodecMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = value == mode,
                        onClick = { onChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, CodecMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
        },
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun TextRow(title: String, detail: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = {
            OutlinedTextField(
                text,
                { text = it; onChange(it) },
                singleLine = true,
                visualTransformation = if (secret && text.isNotEmpty()) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.width(260.dp),
            )
        },
    )
    HorizontalDivider(Modifier.padding(horizontal = Spacing.m), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
