package app.auriel.midas.ffmpeg

import app.auriel.midas.core.AssRenderer
import app.auriel.midas.core.AssSupport
import app.auriel.midas.core.MediaSource
import app.auriel.midas.data.SubtitleStyle
import app.auriel.midas.data.CodecMode
import app.auriel.midas.core.MediaTracks
import app.auriel.midas.core.PlaybackClock
import app.auriel.midas.core.PlaybackEngine
import app.auriel.midas.core.PlaybackState
import app.auriel.midas.core.VideoFrame
import app.auriel.midas.core.VideoFrameSource
import app.auriel.midas.subtitles.SubtitleTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop playback on FFmpeg: any codec and container, embedded audio and subtitle tracks,
 * GPU decoding where available. One engine plays one file at a time; [open] replaces the current one.
 */
class FfmpegEngine : PlaybackEngine, VideoFrameSource, AssSupport {
    /** GPU or CPU video decoding. Applies to the next [open]. */
    @Volatile var decoding: CodecMode = CodecMode.Hardware

    /** Audio language to pick on [open] ("ja", "jpn"…); null = the file's default track. */
    @Volatile var preferredAudioLanguage: String? = null

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _duration = MutableStateFlow<Double?>(null)
    override val duration: StateFlow<Double?> = _duration.asStateFlow()
    private val _tracks = MutableStateFlow(MediaTracks())
    override val tracks: StateFlow<MediaTracks> = _tracks.asStateFlow()
    private val _selectedAudio = MutableStateFlow<String?>(null)
    override val selectedAudio: StateFlow<String?> = _selectedAudio.asStateFlow()
    private val _selectedSubtitle = MutableStateFlow<String?>(null)
    override val selectedSubtitle: StateFlow<String?> = _selectedSubtitle.asStateFlow()
    private val _embedded = MutableStateFlow<Map<String, SubtitleTrack>>(emptyMap())
    override val embeddedSubtitles: StateFlow<Map<String, SubtitleTrack>> = _embedded.asStateFlow()
    private val _decoder = MutableStateFlow<String?>(null)
    /** "Hardware (D3D11VA)" or "Software", for the stats overlay. */
    val videoDecoder: StateFlow<String?> = _decoder.asStateFlow()

    @Volatile private var session: Session? = null
    @Volatile private var wantPlaying = false

    override val clock = PlaybackClock { session?.clockNow() ?: 0.0 }

    override var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    override var speed: Float = 1f
        set(value) {
            val v = value.coerceIn(0.25f, 4f)
            if (v == field) return
            field = v
            session?.onSpeedChanged()
        }

    override var audioDelay: Double = 0.0

    override fun open(source: MediaSource, startAt: Double) {
        session?.close()
        _state.value = PlaybackState.Loading
        _duration.value = null
        _tracks.value = MediaTracks()
        _selectedAudio.value = null
        _selectedSubtitle.value = null
        _embedded.value = emptyMap()
        _decoder.value = null
        val s = Session(this, source, startAt, decoding == CodecMode.Hardware, preferredAudioLanguage)
        session = s
        s.start()
        if (wantPlaying) s.setPlaying(true)
    }

    override fun play() {
        wantPlaying = true
        val s = session ?: return
        if (_state.value == PlaybackState.Ended) {
            _state.value = PlaybackState.Ready
            s.seek(0.0, exact = true)
        }
        s.setPlaying(true)
        _isPlaying.value = true
    }

    override fun pause() {
        wantPlaying = false
        session?.setPlaying(false)
        _isPlaying.value = false
    }

    override fun seek(seconds: Double, exact: Boolean) {
        val s = session ?: return
        if (_state.value == PlaybackState.Ended) _state.value = PlaybackState.Ready
        s.seek(seconds, exact)
    }

    override fun selectAudio(id: String?) {
        val s = session ?: return
        if (id == _selectedAudio.value) return
        _selectedAudio.value = id
        s.selectAudio(id?.toIntOrNull() ?: -1)
    }

    override fun selectSubtitle(id: String?) {
        _selectedSubtitle.value = id
    }

    override fun frameStep(forward: Boolean) {
        val s = session ?: return
        s.frameStep(forward)
        wantPlaying = false
        _isPlaying.value = false
    }

    override fun screenshot(): VideoFrame? = session?.snapshot()

    override fun currentFrame(): VideoFrame? = session?.currentFrame()

    // ---- Full-styling ASS (libass) ----

    @Volatile internal var assScale: Float = 1f
        private set
    @Volatile internal var assStyle: SubtitleStyle = SubtitleStyle()
        private set
    private var scriptLibrary: AssLibrary? = null

    override val assAvailable: Boolean get() = Libass.available

    override fun embeddedAssRenderer(trackId: String): AssRenderer? {
        val r = session?.assRendererFor(trackId) ?: return null
        // The session owns it (it's fed while the file is read); the UI mustn't close it.
        return object : AssRenderer {
            override fun render(time: Double, width: Int, height: Int) = r.render(time, width, height)
            override fun close() {}
        }
    }

    override fun assScriptRenderer(script: String): AssRenderer? {
        // Use this file's library when there is one, so the file's attached fonts work for loaded scripts too.
        val lib = session?.assLibrary() ?: synchronized(this) {
            scriptLibrary ?: AssLibrary.create()?.also { it.setStyle(assScale, assStyle); scriptLibrary = it }
        } ?: return null
        return lib.readScript(script)
    }

    override fun setAssStyle(scale: Float, style: SubtitleStyle) {
        assScale = scale
        assStyle = style
        session?.setAssStyle(scale, style)
        scriptLibrary?.setStyle(scale, style)
    }

    override fun close() {
        session?.close()
        session = null
        _state.value = PlaybackState.Idle
        _isPlaying.value = false
    }

    // ---- Session callbacks (from its threads; stale sessions are ignored) ----

    internal fun onTracks(from: Session, tracks: MediaTracks, duration: Double?, audio: String?) {
        if (from !== session) return
        _tracks.value = tracks
        _duration.value = duration
        _selectedAudio.value = audio
    }

    internal fun onReady(from: Session) {
        if (from !== session) return
        _state.value = PlaybackState.Ready
    }

    internal fun onVideoDecoder(from: Session, name: String) {
        if (from === session) _decoder.value = name
    }

    internal fun onSubtitles(from: Session, updated: Map<String, SubtitleTrack>) {
        if (from === session) _embedded.value = _embedded.value + updated
    }

    internal fun onEnded(from: Session) {
        if (from !== session) return
        from.setPlaying(false)
        wantPlaying = false
        _isPlaying.value = false
        _state.value = PlaybackState.Ended
    }

    internal fun onError(from: Session, message: String) {
        if (from !== session) return
        _state.value = PlaybackState.Error(message)
    }

    companion object {
        /** Unpacks and loads the FFmpeg natives ahead of the first [open] (call from a background thread). */
        fun preload() = runCatching { FfmpegNatives.load() }
    }
}
