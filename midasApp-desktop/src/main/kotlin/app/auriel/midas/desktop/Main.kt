package app.auriel.midas.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.auriel.midas.data.SettingsRepository
import app.auriel.midas.ffmpeg.FfmpegEngine
import app.auriel.midas.library.isSubtitleName
import app.auriel.midas.library.isVideoName
import app.auriel.midas.subtitles.SubtitleFetcher
import app.auriel.midas.ui.FullscreenController
import app.auriel.midas.ui.LocalFullscreen
import app.auriel.midas.ui.theme.AppTheme
import app.auriel.midas.ui.theme.MidasTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Midas desktop. Arguments: files, folders or URLs to play; `--header "Name: value"` adds an HTTP header
 * (Stash's "Open in Midas" passes the Hydrus access key this way).
 */
fun main(args: Array<String>) {
    if (SingleInstance.forwardToRunning(args.toList())) return
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    // Unpack FFmpeg's natives while the window opens.
    Thread({ FfmpegEngine.preload() }, "midas-preload").apply { isDaemon = true; start() }

    val settings = SettingsRepository(FileSettingsStorage())
    val state = AppState(settings)
    val engine = FfmpegEngine()
    val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 8_000; requestTimeoutMillis = 60_000 }
    }
    val fetcher = SubtitleFetcher(http) { settings.current }
    if (args.isNotEmpty()) state.openArgs(args.toList())

    val frameRef = java.util.concurrent.atomic.AtomicReference<java.awt.Frame?>(null)
    SingleInstance.listen { forwarded ->
        EventQueue.invokeLater {
            state.openArgs(forwarded)
            frameRef.get()?.let { it.toFront(); it.requestFocus() }
        }
    }
    // macOS sends files from Finder / "Open With" as events rather than arguments.
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
            Desktop.getDesktop().setOpenFileHandler { e -> EventQueue.invokeLater { state.openArgs(e.files.map { it.absolutePath }) } }
        }
    }

    application {
        val deps = remember {
            AppDependencies(
                settings, engine, fetcher, state,
                pickFile = {
                    val dialog = FileDialog(frameRef.get(), "Open video", FileDialog.LOAD).apply { isVisible = true }
                    if (dialog.directory != null && dialog.file != null) File(dialog.directory, dialog.file) else null
                },
                pickFolder = { title ->
                    val chooser = JFileChooser().apply {
                        dialogTitle = title
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        state.current?.let { (it as? app.auriel.midas.core.MediaSource.File)?.path?.let(::File)?.parentFile }?.let { currentDirectory = it }
                    }
                    if (chooser.showOpenDialog(frameRef.get()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                },
            )
        }
        val icon = remember {
            Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
                ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
        }
        val windowState = rememberWindowState(position = androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.Center), width = 1120.dp, height = 700.dp)
        var placementBeforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
        var boundsBeforeFullscreen by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        var fullscreenOn by remember { mutableStateOf(false) }
        val prefs by settings.settings.collectAsState()
        val title = if (state.screen == Screen.Player) state.current?.displayName?.let { "$it – Midas" } ?: "Midas" else "Midas"

        Window(
            title = title,
            icon = icon,
            state = windowState,
            onCloseRequest = {
                engine.close()
                http.close()
                exitApplication()
            },
        ) {
            DisposableEffect(window) {
                frameRef.set(window)
                val focusListener = object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { if (fullscreenOn) window.isAlwaysOnTop = true }
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) { window.isAlwaysOnTop = false }
                }
                window.addWindowFocusListener(focusListener)
                // Drag and drop files or folders anywhere on the window.
                window.contentPane.dropTarget = object : DropTarget() {
                    override fun drop(e: DropTargetDropEvent) {
                        e.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching { e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull().orEmpty()
                        e.dropComplete(files.isNotEmpty())
                        if (files.isEmpty()) return
                        val sub = files.singleOrNull()?.takeIf { isSubtitleName(it.name) }
                        if (sub != null && state.screen == Screen.Player) {
                            state.message = "Load subtitles from the player's subtitle menu"
                        }
                        state.openArgs(files.filter { it.isDirectory || isVideoName(it.name) || isSubtitleName(it.name) }.map { it.absolutePath })
                    }
                }
                onDispose { window.removeWindowFocusListener(focusListener) }
            }

            val fullscreen = remember {
                FullscreenController { on ->
                    // WindowPlacement.Fullscreen keeps the taskbar on Windows, so cover the monitor ourselves
                    // with the window frame pushed just off-screen.
                    if (on) {
                        placementBeforeFullscreen = windowState.placement
                        boundsBeforeFullscreen = window.bounds
                        if (windowState.placement != WindowPlacement.Floating) windowState.placement = WindowPlacement.Floating
                        val screen = window.graphicsConfiguration.bounds
                        val insets = window.insets
                        window.setBounds(
                            screen.x - insets.left,
                            screen.y - insets.top,
                            screen.width + insets.left + insets.right,
                            screen.height + insets.top + insets.bottom,
                        )
                        fullscreenOn = true
                        window.isAlwaysOnTop = true
                    } else {
                        fullscreenOn = false
                        window.isAlwaysOnTop = false
                        boundsBeforeFullscreen?.let { window.bounds = it }
                        windowState.placement = placementBeforeFullscreen
                    }
                }
            }
            MidasTheme(AppTheme.fromName(prefs.theme)) {
                CompositionLocalProvider(LocalFullscreen provides fullscreen) {
                    MidasApp(deps)
                }
            }
        }
    }
}
