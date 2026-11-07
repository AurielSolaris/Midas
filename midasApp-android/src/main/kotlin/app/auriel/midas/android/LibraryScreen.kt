package app.auriel.midas.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.auriel.midas.data.SavedPlaylist
import app.auriel.midas.data.SettingsRepository
import app.auriel.midas.library.NaturalOrder
import app.auriel.midas.library.isVideoName
import app.auriel.midas.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class LibraryVideo(val uri: Uri, val name: String)
private data class FolderEntry(val uri: Uri, val name: String, val folder: Boolean)

private fun persistRead(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "Video"

private fun folderContents(context: Context, tree: Uri, documentId: String): List<FolderEntry> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
    val result = mutableListOf<FolderEntry>()
    context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            val mime = cursor.getString(2) ?: ""
            val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
            if (folder || mime.startsWith("video/") || isVideoName(name))
                result += FolderEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, folder)
        }
    } ?: error("This folder cannot be read")
    return result.sortedWith { a, b ->
        if (a.folder != b.folder) if (a.folder) -1 else 1 else NaturalOrder.compare(a.name, b.name)
    }
}

@Composable
internal fun LibraryScreen(settings: SettingsRepository, error: String?, open: (Uri, String?) -> Unit, playQueue: (List<LibraryVideo>, Int) -> Unit) {
    val context = LocalContext.current
    val prefs by settings.settings.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<Uri?>(null) }
    var path by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var playlistId by remember { mutableStateOf<String?>(null) }
    var playlistVideos by remember { mutableStateOf<List<LibraryVideo>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var showLink by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    val currentPlaylist = prefs.playlists.firstOrNull { it.id == playlistId }
    BackHandler(enabled = folder != null || currentPlaylist != null) {
        if (folder != null && path.size > 1) path = path.dropLast(1)
        else if (folder != null) { folder = null; path = emptyList() }
        else playlistId = null
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) open(uri, null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            persistRead(context, uri)
            settings.update { it.copy(savedFolders = (it.savedFolders + uri.toString()).distinct()) }
            folder = uri
            path = listOf(DocumentsContract.getTreeDocumentId(uri) to (uri.lastPathSegment?.substringAfterLast(':') ?: "Folder"))
            playlistId = null
        } catch (e: Exception) { message = "Couldn't save folder access: ${e.message}" }
    }
    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = playlistId
        if (id != null && uris.isNotEmpty()) {
            val granted = uris.filter { uri -> runCatching { persistRead(context, uri) }.isSuccess }
            if (granted.size != uris.size) message = "Some files could not be saved for later access."
            settings.update { s -> s.copy(playlists = s.playlists.map { p -> if (p.id == id) p.copy(files = (p.files + granted.map(Uri::toString)).distinct()) else p }) }
        }
    }
    LaunchedEffect(folder, path) {
        val tree = folder ?: return@LaunchedEffect
        val id = path.lastOrNull()?.first ?: return@LaunchedEffect
        loading = true
        entries = emptyList()
        try { entries = withContext(Dispatchers.IO) { folderContents(context, tree, id) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = "Couldn't open folder: ${e.message}" }
        finally { loading = false }
    }
    LaunchedEffect(currentPlaylist) {
        val list = currentPlaylist ?: run { playlistVideos = emptyList(); return@LaunchedEffect }
        playlistVideos = withContext(Dispatchers.IO) {
            list.files.map { value ->
                val uri = Uri.parse(value)
                LibraryVideo(uri, runCatching { displayName(context, uri) }.getOrDefault(uri.lastPathSegment ?: "Unavailable video"))
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Midas", style = MaterialTheme.typography.headlineMedium)
        if (error != null || message != null) Text(message ?: error.orEmpty(), color = MaterialTheme.colorScheme.error)
        if (folder != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (path.size > 1) path = path.dropLast(1) else { folder = null; path = emptyList() } }) { Text("Back") }
                Text(path.lastOrNull()?.second ?: "Folder", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val videos = entries.filterNot { it.folder }.map { LibraryVideo(it.uri, it.name) }
            LazyColumn {
                items(entries, key = { it.uri.toString() }) { entry ->
                    TextButton(onClick = {
                        if (entry.folder) path = path + (DocumentsContract.getDocumentId(entry.uri) to entry.name)
                        else playQueue(videos, videos.indexOfFirst { it.uri == entry.uri })
                    }, modifier = Modifier.fillMaxWidth()) { Text((if (entry.folder) "📁  " else "▶  ") + entry.name) }
                }
                if (!loading && entries.isEmpty()) item { Text("No videos or folders found.") }
            }
        } else if (currentPlaylist != null) {
            Row {
                TextButton(onClick = { playlistId = null }) { Text("Back") }
                Text(currentPlaylist.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { addFiles.launch(arrayOf("video/*", "application/x-matroska")) }) { Text("Add videos") }
            }
            if (playlistVideos.isEmpty()) Text("Add videos to this playlist.")
            LazyColumn {
                items(playlistVideos, key = { it.uri.toString() }) { video ->
                    Row {
                        TextButton(onClick = { playQueue(playlistVideos, playlistVideos.indexOf(video)) }, modifier = Modifier.weight(1f)) { Text(video.name) }
                        TextButton(onClick = { settings.update { s -> s.copy(playlists = s.playlists.map { p -> if (p.id == playlistId) p.copy(files = p.files - video.uri.toString()) else p }) } }, modifier = Modifier.semantics { contentDescription = "Remove ${video.name} from playlist" }) { Text("Remove") }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { videoPicker.launch(arrayOf("video/*", "audio/*", "application/x-matroska")) }) { Text("Open video") }
                        TextButton(onClick = { showLink = true }) { Text("Open link") }
                    }
                }
                item { Text("Theme", style = MaterialTheme.typography.titleMedium) }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppTheme.entries.forEach { theme -> FilterChip(selected = prefs.theme == theme.name, onClick = { settings.update { it.copy(theme = theme.name) } }, label = { Text(theme.label) }) }
                    }
                }
                item {
                    Row { Text("Autoplay next", Modifier.weight(1f)); Switch(checked = prefs.autoplayNext, onCheckedChange = { enabled -> settings.update { it.copy(autoplayNext = enabled) } }) }
                }
                item { Row { Text("Folders", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = { folderPicker.launch(null) }) { Text("Add folder") } } }
                items(prefs.savedFolders) { value ->
                    Row {
                        TextButton(onClick = { val uri = Uri.parse(value); folder = uri; path = listOf(DocumentsContract.getTreeDocumentId(uri) to (uri.lastPathSegment?.substringAfterLast(':') ?: "Folder")) }, modifier = Modifier.weight(1f)) { Text(Uri.parse(value).lastPathSegment?.substringAfterLast(':') ?: "Folder") }
                        TextButton(onClick = { settings.update { it.copy(savedFolders = it.savedFolders - value) } }, modifier = Modifier.semantics { contentDescription = "Remove folder ${Uri.parse(value).lastPathSegment?.substringAfterLast(':') ?: "Folder"}" }) { Text("Remove") }
                    }
                }
                item { Row { Text("Playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = { showCreate = true }) { Text("New playlist") } } }
                items(prefs.playlists, key = { it.id }) { playlist ->
                    Row {
                        TextButton(onClick = { playlistId = playlist.id }, modifier = Modifier.weight(1f)) { Text("${playlist.name} (${playlist.files.size})") }
                        TextButton(onClick = { settings.update { s -> s.copy(playlists = s.playlists.filterNot { it.id == playlist.id }) } }, modifier = Modifier.semantics { contentDescription = "Delete playlist ${playlist.name}" }) { Text("Delete") }
                    }
                }
                item { Text("Recent", style = MaterialTheme.typography.titleMedium) }
                items(prefs.recent, key = { it.location }) { recent -> TextButton(onClick = { open(Uri.parse(recent.location), null) }) { Text(recent.title) } }
            }
        }
    }
    if (showCreate) AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("New playlist") }, text = { OutlinedTextField(newName, { newName = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = { val p = SavedPlaylist(UUID.randomUUID().toString(), newName.trim()); settings.update { it.copy(playlists = it.playlists + p) }; playlistId = p.id; newName = ""; showCreate = false }) { Text("Create") } }, dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } })
    if (showLink) AlertDialog(onDismissRequest = { showLink = false }, title = { Text("Open link") }, text = { Column { OutlinedTextField(link, { link = it }, label = { Text("Video URL") }); OutlinedTextField(headers, { headers = it }, label = { Text("HTTP headers (optional)") }) } },
        confirmButton = { TextButton(enabled = link.trim().startsWith("http://") || link.trim().startsWith("https://"), onClick = { open(Uri.parse(link.trim()), headers); showLink = false }) { Text("Play") } }, dismissButton = { TextButton(onClick = { showLink = false }) { Text("Cancel") } })
}
