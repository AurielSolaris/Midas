package app.auriel.midas.android.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.auriel.midas.core.*
import app.auriel.midas.subtitles.SubtitleTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Media3-backed player. All player operations are made on the Android main thread. */
class AndroidPlaybackEngine(context: Context) : PlaybackEngine {
    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context.applicationContext, http)))
        .build()
    private val stateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state = stateFlow.asStateFlow()
    private val playingFlow = MutableStateFlow(false)
    override val isPlaying = playingFlow.asStateFlow()
    private val durationFlow = MutableStateFlow<Double?>(null)
    override val duration = durationFlow.asStateFlow()
    private val tracksFlow = MutableStateFlow(MediaTracks())
    override val tracks = tracksFlow.asStateFlow()
    private val audioFlow = MutableStateFlow<String?>(null)
    override val selectedAudio = audioFlow.asStateFlow()
    private val subtitleFlow = MutableStateFlow<String?>(null)
    override val selectedSubtitle = subtitleFlow.asStateFlow()
    override val embeddedSubtitles = MutableStateFlow<Map<String, SubtitleTrack>>(emptyMap()).asStateFlow()
    private var groups: List<Tracks.Group> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { refreshState() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playingFlow.value = isPlaying }
            override fun onPlayerError(error: PlaybackException) { stateFlow.value = PlaybackState.Error(error.message ?: "Playback failed") }
            override fun onTracksChanged(tracks: Tracks) { refreshTracks(tracks) }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { refreshState() }
        })
    }

    override val clock = PlaybackClock { player.currentPosition / 1000.0 }
    override var volume: Float
        get() = player.volume
        set(value) { player.volume = value.coerceIn(0f, 1f) }
    override var speed: Float
        get() = player.playbackParameters.speed
        set(value) { player.setPlaybackSpeed(value.coerceIn(0.25f, 4f)) }
    override var audioDelay: Double = 0.0

    override fun open(source: MediaSource, startAt: Double) {
        val location = when (source) {
            is MediaSource.File -> android.net.Uri.fromFile(java.io.File(source.path)).toString()
            is MediaSource.ContentUri -> source.uri
            is MediaSource.Http -> source.url
        }
        http.setDefaultRequestProperties((source as? MediaSource.Http)?.headers ?: emptyMap())
        stateFlow.value = PlaybackState.Loading
        durationFlow.value = null
        tracksFlow.value = MediaTracks()
        audioFlow.value = null
        subtitleFlow.value = null
        player.setMediaItem(MediaItem.fromUri(location), (startAt.coerceAtLeast(0.0) * 1000).toLong())
        player.prepare()
    }
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seek(seconds: Double, exact: Boolean) = player.seekTo((seconds.coerceAtLeast(0.0) * 1000).toLong())
    override fun frameStep(forward: Boolean) { pause(); seek(clock.now() + if (forward) 1.0 / 30 else -1.0 / 30, true) }
    var captureFrame: (() -> VideoFrame?)? = null
    override fun screenshot(): VideoFrame? = captureFrame?.invoke()
    override fun selectAudio(id: String?) = select(id, C.TRACK_TYPE_AUDIO)
    override fun selectSubtitle(id: String?) = select(id, C.TRACK_TYPE_TEXT)
    override fun close() { player.stop(); player.clearMediaItems(); stateFlow.value = PlaybackState.Idle }
    fun release() = player.release()

    private fun select(id: String?, type: Int) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, id == null)
        val pieces = id?.split(':')
        val group = pieces?.getOrNull(0)?.toIntOrNull()?.let { groups.getOrNull(it) }
        val index = pieces?.getOrNull(1)?.toIntOrNull()
        if (group != null && index != null && group.type == type && index in 0 until group.length) {
            builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
        }
        player.trackSelectionParameters = builder.build()
        if (type == C.TRACK_TYPE_AUDIO) audioFlow.value = id else subtitleFlow.value = id
    }

    private fun refreshState() {
        durationFlow.value = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0)
        if (player.playerError != null) return
        stateFlow.value = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackState.Loading
            Player.STATE_READY -> PlaybackState.Ready
            Player.STATE_ENDED -> PlaybackState.Ended
            else -> PlaybackState.Idle
        }
    }

    private fun refreshTracks(tracks: Tracks) {
        groups = tracks.groups
        fun list(type: Int): List<TrackInfo> = groups.flatMapIndexed { groupIndex, group ->
            if (group.type != type) emptyList() else (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val f = group.getTrackFormat(index)
                val id = "$groupIndex:$index"
                if (group.isTrackSelected(index)) {
                    if (type == C.TRACK_TYPE_AUDIO) audioFlow.value = id
                    if (type == C.TRACK_TYPE_TEXT) subtitleFlow.value = id
                }
                TrackInfo(id, f.sampleMimeType?.substringAfter('/') ?: "unknown", f.language, f.label, group.isTrackSelected(index), false, true,
                    if (type == C.TRACK_TYPE_VIDEO && f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null)
            }
        }
        tracksFlow.value = MediaTracks(video = list(C.TRACK_TYPE_VIDEO), audio = list(C.TRACK_TYPE_AUDIO), subtitle = list(C.TRACK_TYPE_TEXT))
        refreshState()
    }
}
