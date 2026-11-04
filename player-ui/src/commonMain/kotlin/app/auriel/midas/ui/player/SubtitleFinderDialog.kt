package app.auriel.midas.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.auriel.midas.subtitles.AnimeQuery
import app.auriel.midas.subtitles.MediaGuess
import app.auriel.midas.subtitles.MediaGuesser
import app.auriel.midas.subtitles.ProviderResult
import app.auriel.midas.subtitles.RemoteSubtitle
import app.auriel.midas.subtitles.SubtitleFetcher
import app.auriel.midas.subtitles.SubtitleTrack
import app.auriel.midas.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class FinderTab(val label: String) { YouTube("YouTube"), Anime("Anime"), Link("Link") }

private sealed interface Search {
    data object Idle : Search
    data object Running : Search
    data class Done(val results: List<ProviderResult>, val note: String? = null) : Search
    data class Failed(val message: String) : Search
}

/**
 * Finds subtitles online. Opens on YouTube when the video has a YouTube id, otherwise on Anime,
 * pre-filled from the file's tags/name, and searches straight away when it has enough to go on.
 */
@Composable
internal fun SubtitleFinderDialog(
    fetcher: SubtitleFetcher,
    guess: MediaGuess,
    language: String,
    onLoaded: (SubtitleTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (guess.youtubeId != null || guess.title == null) FinderTab.YouTube else FinderTab.Anime) }
    var youtube by remember { mutableStateOf(guess.youtubeId?.let { "https://www.youtube.com/watch?v=$it" }.orEmpty()) }
    var title by remember { mutableStateOf(guess.title.orEmpty()) }
    var season by remember { mutableStateOf(guess.season?.toString().orEmpty()) }
    var episode by remember { mutableStateOf(guess.episode?.toString().orEmpty()) }
    var lang by remember { mutableStateOf(language) }
    var link by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<Search>(Search.Idle) }
    var downloading by remember { mutableStateOf<RemoteSubtitle?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(block: suspend () -> Search) {
        job?.cancel()
        downloadError = null
        search = Search.Running
        job = scope.launch {
            search = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Search.Failed(e.message ?: e.toString())
            }
        }
    }

    fun searchYouTube() {
        val id = MediaGuesser.youtubeIdFromUrl(youtube)
        if (id == null) { search = Search.Failed("That isn't a YouTube link or video id."); return }
        run { Search.Done(listOf(ProviderResult(SubtitleFetcher.YOUTUBE, fetcher.youtube(id)))) }
    }

    fun searchAnime() {
        if (title.isBlank()) { search = Search.Failed("Enter the show's title."); return }
        run {
            val (anilist, results) = fetcher.anime(
                AnimeQuery(title.trim(), season.toIntOrNull(), episode.toIntOrNull(), lang.trim().lowercase().ifEmpty { "en" }),
            )
            Search.Done(results, anilist?.let { "AniList match: ${it.titles.firstOrNull() ?: title} (#${it.id})" })
        }
    }

    fun loadLink() {
        if (link.isBlank()) return
        job?.cancel()
        downloadError = null
        search = Search.Running
        job = scope.launch {
            try {
                onLoaded(fetcher.fromUrl(link))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                search = Search.Failed(e.message ?: e.toString())
            }
        }
    }

    fun pick(item: RemoteSubtitle) {
        downloading = item
        downloadError = null
        scope.launch {
            try {
                onLoaded(item.download())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadError = "${item.title}: ${e.message ?: e}"
            } finally {
                downloading = null
            }
        }
    }

    // Search as soon as the tab has something to search for.
    LaunchedEffect(tab) {
        search = Search.Idle
        when (tab) {
            FinderTab.YouTube -> if (MediaGuesser.youtubeIdFromUrl(youtube) != null) searchYouTube()
            FinderTab.Anime -> if (title.isNotBlank() && fetcher.animeProviders.isNotEmpty()) searchAnime()
            FinderTab.Link -> {}
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // MD3 dialog: extra-large corners on surface-container-high.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(Spacing.m).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(top = Spacing.l)) {
                Text(
                    "Find subtitles",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
                PrimaryTabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(top = Spacing.m),
                ) {
                    FinderTab.entries.forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                    }
                }

                Column(
                    Modifier.padding(horizontal = Spacing.l).padding(top = Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    when (tab) {
                        FinderTab.YouTube -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            OutlinedTextField(
                                value = youtube, onValueChange = { youtube = it },
                                label = { Text("YouTube link or video id") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { searchYouTube() }),
                            )
                            Button(onClick = ::searchYouTube) { Text("Find") }
                        }
                        FinderTab.Anime -> {
                            OutlinedTextField(
                                value = title, onValueChange = { title = it },
                                label = { Text("Title") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { searchAnime() }),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                NumberField("Season", season, Modifier.weight(1f)) { season = it }
                                NumberField("Episode", episode, Modifier.weight(1f)) { episode = it }
                                OutlinedTextField(
                                    value = lang, onValueChange = { lang = it.take(3) },
                                    label = { Text("Lang") },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                )
                                Button(onClick = ::searchAnime) { Text("Search") }
                            }
                            if (fetcher.animeProviders.isEmpty()) {
                                Notice("No anime subtitle sources are set up. Add a free Jimaku, OpenSubtitles or SubDL key in Settings → Video & subtitles.")
                            }
                        }
                        FinderTab.Link -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            OutlinedTextField(
                                value = link, onValueChange = { link = it },
                                label = { Text("Link to an .srt, .vtt, .ass or .zip file") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { loadLink() }),
                            )
                            Button(onClick = ::loadLink) { Text("Load") }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().padding(top = Spacing.s).height(4.dp)) {
                    if (search == Search.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                downloadError?.let { Box(Modifier.padding(horizontal = Spacing.l)) { Notice(it, error = true) } }

                Box(Modifier.weight(1f, fill = false)) {
                    when (val s = search) {
                        is Search.Failed -> Box(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) { Notice(s.message, error = true) }
                        is Search.Done -> ResultList(s, downloading, ::pick)
                        else -> {}
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(Spacing.l, Spacing.s, Spacing.l, Spacing.l),
                    horizontalArrangement = Arrangement.End,
                ) { TextButton(onClick = onDismiss) { Text("Close") } }
            }
        }
    }
}

@Composable
private fun ResultList(done: Search.Done, downloading: RemoteSubtitle?, onPick: (RemoteSubtitle) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        done.note?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                )
            }
        }
        done.results.forEach { r ->
            item(key = "h-${r.provider}") {
                Text(
                    r.provider + if (r.error == null) " · ${r.items.size}" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs),
                )
            }
            when {
                r.error != null -> item(key = "e-${r.provider}") {
                    Box(Modifier.padding(horizontal = Spacing.l)) { Notice(r.error.orEmpty(), error = true) }
                }
                r.items.isEmpty() -> item(key = "n-${r.provider}") {
                    Text(
                        "Nothing found. Try another title (English or romaji) or remove the episode number.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
                else -> items(r.items) { item ->
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = item.detail.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = if (downloading === item) ({ CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }) else null,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.clickable(enabled = downloading == null) { onPick(item) }.padding(horizontal = Spacing.s),
                    )
                }
            }
            item(key = "d-${r.provider}") { HorizontalDivider(Modifier.padding(top = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
        if (error) Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
