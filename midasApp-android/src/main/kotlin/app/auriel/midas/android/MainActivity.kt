package app.auriel.midas.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auriel.midas.android.player.AndroidPlaybackEngine
import app.auriel.midas.core.MediaSource
import app.auriel.midas.data.SettingsRepository
import app.auriel.midas.data.SettingsStorage
import app.auriel.midas.subtitles.MediaGuesser
import app.auriel.midas.subtitles.SubtitleFetcher
import app.auriel.midas.ui.FullscreenController
import app.auriel.midas.ui.LocalFullscreen
import app.auriel.midas.ui.player.PlayerItem
import app.auriel.midas.ui.player.VideoPlayer
import app.auriel.midas.ui.theme.AppTheme
import app.auriel.midas.ui.theme.MidasTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

class MainActivity : ComponentActivity() {
    private val engine by lazy { AndroidPlaybackEngine(applicationContext) }
    private val settings by lazy {
        val preferences = getSharedPreferences("midas", MODE_PRIVATE)
        SettingsRepository(object : SettingsStorage {
            override fun read(): String? = preferences.getString("settings", null)
            override fun write(contents: String) { preferences.edit().putString("settings", contents).apply() }
        })
    }
    private val http = HttpClient(CIO) { install(HttpTimeout) { connectTimeoutMillis = 8_000; requestTimeoutMillis = 60_000 } }
    private val fetcher by lazy { SubtitleFetcher(http) { settings.current } }
    private var current by mutableStateOf<PlayerItem?>(null)
    private var lastError by mutableStateOf<String?>(null)
    private var queue by mutableStateOf<List<LibraryVideo>>(emptyList())
    private var queueIndex by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            val uris = savedInstanceState.getStringArrayList("queue_uris")
            val names = savedInstanceState.getStringArrayList("queue_names")
            val index = savedInstanceState.getInt("queue_index", -1)
            if (uris != null && names != null && uris.size == names.size && index in uris.indices) {
                openQueue(uris.zip(names).map { (uri, name) -> LibraryVideo(Uri.parse(uri), name) }, index)
            } else savedInstanceState.getString("source")?.let { openUri(Uri.parse(it), savedInstanceState.getString("headers")) }
        } else handleIntent(intent)
        setContent {
            val preferences by settings.settings.collectAsState()
            MidasTheme(AppTheme.fromName(preferences.theme)) {
                val fullscreen = remember {
                    FullscreenController { enabled ->
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = if (enabled) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
                    }
                }
                CompositionLocalProvider(LocalFullscreen provides fullscreen) {
                    val item = current
                    if (item == null) Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                        LibraryScreen(settings, lastError, ::openUri, ::openQueue)
                    }
                    else {
                        BackHandler { persistPosition(); engine.pause(); current = null; fullscreen.set(false) }
                        VideoPlayer(engine, item, settings, fetcher, queueIndex > 0, queueIndex >= 0 && queueIndex < queue.lastIndex,
                        queue.getOrNull(queueIndex + 1)?.name, { playQueueItem(queueIndex - 1) }, { playQueueItem(queueIndex + 1) }, onClose = {
                        persistPosition()
                        engine.pause()
                        current = null
                        fullscreen.set(false)
                    }, resumeMinDuration = 0.0, resumeMinPosition = 5.0)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onSaveInstanceState(outState: Bundle) {
        persistPosition()
        current?.let { item ->
            outState.putString("source", item.key)
            (item.source as? MediaSource.Http)?.headers?.let { headers -> outState.putString("headers", headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
        }
        if (queueIndex in queue.indices) {
            outState.putStringArrayList("queue_uris", ArrayList(queue.map { it.uri.toString() }))
            outState.putStringArrayList("queue_names", ArrayList(queue.map { it.name }))
            outState.putInt("queue_index", queueIndex)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { persistPosition(); engine.pause(); super.onStop() }
    override fun onDestroy() { engine.release(); http.close(); super.onDestroy() }

    private fun persistPosition() {
        val item = current ?: return
        val position = engine.clock.now()
        val duration = engine.duration.value
        settings.saveResume(item.key, position.takeIf { it > 5 && (duration == null || it < duration - 5) })
    }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { openUri(it, null) }
    }
    private fun openUri(uri: Uri, headers: String?) {
        queue = emptyList()
        queueIndex = -1
        playUri(uri, headers)
    }
    private fun openQueue(videos: List<LibraryVideo>, index: Int) {
        queue = videos
        playQueueItem(index)
    }
    private fun playQueueItem(index: Int) {
        val video = queue.getOrNull(index) ?: return
        queueIndex = index
        playUri(video.uri, null, video.name)
    }
    private fun playUri(uri: Uri, headers: String?, displayName: String? = null) {
        if (uri.scheme !in setOf("content", "file", "http", "https")) { lastError = "Unsupported link or file."; return }
        if (uri.scheme == "content") runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = if (uri.scheme == "content") runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() else uri.lastPathSegment
        val name = displayName ?: title ?: "Video"
        val key = uri.toString()
        val source = when (uri.scheme) {
            "http", "https" -> MediaSource.Http(key, headers.orEmpty().lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() })
            else -> MediaSource.ContentUri(key)
        }
        if (current != null) persistPosition()
        current = PlayerItem(key, source, name.substringBeforeLast('.'), null, MediaGuesser.guess(name, url = key))
        settings.addRecent(app.auriel.midas.data.RecentItem(key, name, System.currentTimeMillis()))
        lastError = null
    }
}
