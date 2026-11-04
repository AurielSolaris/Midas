package app.auriel.midas.ffmpeg

import app.auriel.midas.core.Chapter
import app.auriel.midas.core.Languages
import app.auriel.midas.core.MediaSource
import app.auriel.midas.core.MediaTracks
import app.auriel.midas.core.TrackInfo
import app.auriel.midas.core.VideoFrame
import app.auriel.midas.subtitles.SubtitleBitmap
import app.auriel.midas.subtitles.SubtitleCue
import app.auriel.midas.subtitles.SubtitleParser
import app.auriel.midas.subtitles.SubtitleTrack
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVSubtitle
import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL
import org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_DEFAULT
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX
import org.bytedeco.ffmpeg.global.avcodec.SUBTITLE_ASS
import org.bytedeco.ffmpeg.global.avcodec.SUBTITLE_BITMAP
import org.bytedeco.ffmpeg.global.avcodec.SUBTITLE_TEXT
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_clone
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_decode_subtitle2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_hw_config
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avcodec.avsubtitle_free
import org.bytedeco.ffmpeg.global.avfilter.av_buffersink_get_frame
import org.bytedeco.ffmpeg.global.avfilter.av_buffersrc_add_frame
import org.bytedeco.ffmpeg.global.avfilter.avfilter_get_by_name
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_alloc
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_config
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_create_filter
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_free
import org.bytedeco.ffmpeg.global.avfilter.avfilter_graph_parse_ptr
import org.bytedeco.ffmpeg.global.avfilter.avfilter_inout_alloc
import org.bytedeco.ffmpeg.global.avfilter.avfilter_inout_free
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_DEFAULT
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_FORCED
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_network_init
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT2020_CL
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT2020_NCL
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE170M
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_SUBTITLE
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_CHANNEL_ORDER_UNSPEC
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_NONE
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VAAPI
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NONE
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_buffer_ref
import org.bytedeco.ffmpeg.global.avutil.av_buffer_unref
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_describe
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_get_sample_fmt_name
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create
import org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data
import org.bytedeco.ffmpeg.global.avutil.av_strdup
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_BT2020
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_DEFAULT
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU601
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU709
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCoefficients
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.global.swscale.sws_setColorspaceDetails
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min

/**
 * One opened file. Three threads:
 * - **demux** reads packets, routes them to the decoders, decodes text subtitles inline and handles seeks;
 * - **video** decodes (GPU first), converts to BGRA and queues a few frames ahead;
 * - **audio** decodes, runs atempo/resample/volume, and writes to the sound card, which drives the clock.
 *
 * The UI pulls frames with [currentFrame], which picks the newest one whose time has come.
 * Only the demux thread frees the format context, after the decoder threads have finished.
 */
internal class Session(
    private val engine: FfmpegEngine,
    private val source: MediaSource,
    private val startAt: Double,
    private val hardwareDecoding: Boolean,
    private val preferredAudio: String?,
) {
    @Volatile var closed = false
        private set

    // ---- Streams (set once open, before the decoder threads start) ----
    private var fmt: AVFormatContext? = null
    private var videoStream: AVStream? = null
    @Volatile private var audioStreamIndex = -1
    private var videoTimeBase = 0.0
    private var frameDuration = 1.0 / 30
    private var duration: Double? = null

    // ---- Queues and seeking ----
    private val serial = AtomicInteger(0)
    private val videoPackets = PacketQueue(softLimit = 150)
    private val audioPackets = PacketQueue(softLimit = 400)
    private class SeekRequest(val target: Double, val exact: Boolean, val audioStream: Int? = null)
    private val pendingSeek = AtomicReference<SeekRequest?>()

    // ---- Clock ----
    @Volatile private var playing = false
    /** While set, the clock reads this (just after a seek, until audio or video re-anchors it). */
    @Volatile private var seekTarget: Double? = startAt.takeIf { it > 0 }
    @Volatile private var stepPosition: Double? = null
    /** Wall clock for files without audio, or once the audio has run out before the video. */
    @Volatile private var wallBase = startAt
    @Volatile private var wallAnchorNanos = System.nanoTime()
    @Volatile private var wallClock = true
    private var lastClock = 0.0
    private var lastClockNanos = 0L
    private val audio: AudioOutput? = runCatching { AudioOutput() }.getOrNull()

    // ---- Video frames ----
    private val frameLock = Any()
    private val frames = ArrayDeque<VideoFrame>()
    @Volatile private var current: VideoFrame? = null
    @Volatile private var showNextImmediately = true
    private val pool = ArrayDeque<ByteArray>()
    @Volatile private var videoEof = false
    @Volatile private var audioEof = false

    // ---- Subtitles ----
    private class SubtitleStream(
        val id: String,
        val ctx: AVCodecContext,
        val timeBase: Double,
        val label: String,
        val language: String?,
        val topStyles: Set<String>,
        val bitmap: Boolean,
        /** Full-styling renderer for ASS/SSA tracks (when libass is available). */
        val ass: AssTrackRenderer? = null,
    ) {
        val cues = LinkedHashMap<Pair<Long, String>, SubtitleCue>()
        /** Picture cues still on screen with no end time yet: the next display set ends them (PGS, DVB). */
        val open = ArrayList<Pair<Long, String>>()
        var dirty = false
    }
    private val subtitleStreams = HashMap<Int, SubtitleStream>()

    private var demuxThread: Thread? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun start() {
        demuxThread = thread("midas-demux") { runDemux() }
    }

    fun close() {
        closed = true
        // The demux thread frees everything once the decoders have stopped.
    }

    // =============================================================================================
    // Controls (any thread)
    // =============================================================================================

    fun setPlaying(on: Boolean) {
        if (on == playing) return
        if (on) {
            stepPosition?.let { pos ->
                stepPosition = null
                seek(pos, exact = true)
            }
            wallAnchorNanos = System.nanoTime()
            playing = true
            audio?.start()
        } else {
            wallBase = clockNow()
            playing = false
            audio?.stop()
        }
    }

    fun seek(seconds: Double, exact: Boolean, audioStream: Int? = null) {
        val d = duration
        val target = if (d != null && d > 0) seconds.coerceIn(0.0, d) else seconds.coerceAtLeast(0.0)
        stepPosition = null
        seekTarget = target
        pendingSeek.set(SeekRequest(target, exact, audioStream))
    }

    fun selectAudio(index: Int) = seek(clockNow(), exact = true, audioStream = index)

    fun frameStep(forward: Boolean) {
        setPlaying(false)
        if (forward) {
            synchronized(frameLock) {
                val next = frames.removeFirstOrNull() ?: return
                recycle(current)
                current = next
                stepPosition = next.pts
            }
        } else {
            val from = stepPosition ?: current?.pts ?: clockNow()
            seek(from - frameDuration, exact = true)
        }
    }

    fun clockNow(): Double {
        stepPosition?.let { return it }
        seekTarget?.let { return it }
        val a = audio
        val raw = if (!wallClock && a != null && a.isAnchored) {
            a.position() - engine.audioDelay
        } else {
            if (playing) wallBase + (System.nanoTime() - wallAnchorNanos) / 1e9 * engine.speed else wallBase
        }
        // The sound card reports progress in steps of a few ms; smooth them out so motion stays even.
        val nowNanos = System.nanoTime()
        val predicted = lastClock + (nowNanos - lastClockNanos) / 1e9 * engine.speed
        val smoothed = if (playing && abs(raw - predicted) < 0.05) predicted + (raw - predicted) * 0.1 else raw
        lastClock = smoothed
        lastClockNanos = nowNanos
        return smoothed
    }

    fun onSpeedChanged() {
        // atempo is rebuilt by the audio thread on the next flush; re-seeking keeps sound and clock exact.
        wallBase = clockNow()
        wallAnchorNanos = System.nanoTime()
        if (audioStreamIndex >= 0) seek(clockNow(), exact = true)
    }

    /** The frame due now. Called by the UI every frame. */
    fun currentFrame(): VideoFrame? {
        synchronized(frameLock) {
            if (showNextImmediately) {
                val first = frames.removeFirstOrNull()
                if (first != null) {
                    recycle(current)
                    current = first
                    showNextImmediately = false
                }
                if (current == null) return null
            }
            val t = clockNow()
            while (true) {
                val next = frames.firstOrNull() ?: break
                if (next.pts > t + 0.008) break
                frames.removeFirst()
                recycle(current)
                current = next
            }
            return current
        }
    }

    fun snapshot(): VideoFrame? = synchronized(frameLock) {
        current?.let { VideoFrame(it.width, it.height, it.rowBytes, it.pixels.copyOf(), it.pts, it.pixelAspect) }
    }

    private fun recycle(frame: VideoFrame?) {
        if (frame != null && pool.size < 6) pool.addLast(frame.pixels)
    }

    private fun takeBuffer(size: Int): ByteArray = synchronized(frameLock) {
        while (pool.isNotEmpty()) {
            val b = pool.removeFirst()
            if (b.size == size) return b
        }
        return ByteArray(size)
    }

    // =============================================================================================
    // Demux thread
    // =============================================================================================

    private fun runDemux() {
        try {
            FfmpegNatives.load()
            if (!openInput()) return
            videoThread = videoStream?.let { thread("midas-video") { runVideo(it) } }
            audioThread = thread("midas-audio") { runAudio() }
            if (startAt > 0) seek(startAt, exact = true)
            engine.onReady(this)
            demuxLoop()
        } catch (t: Throwable) {
            if (!closed) engine.onError(this, t.message ?: t.toString())
        } finally {
            closed = true
            videoThread?.join(2_000)
            audioThread?.join(2_000)
            videoPackets.clear()
            audioPackets.clear()
            subtitleStreams.values.forEach { avcodec_free_context(it.ctx) }
            assLibrary?.close()
            fmt?.let { avformat_close_input(it) }
            audio?.close()
        }
    }

    private fun openInput(): Boolean {
        val url = when (source) {
            is MediaSource.File -> source.path
            is MediaSource.Http -> source.url
            is MediaSource.ContentUri -> source.uri
        }
        val options = AVDictionary(null as Pointer?)
        if (source is MediaSource.Http) {
            avformat_network_init()
            if (source.headers.isNotEmpty()) {
                av_dict_set(options, "headers", source.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }, 0)
            }
            av_dict_set(options, "reconnect", "1", 0)
            av_dict_set(options, "rw_timeout", "20000000", 0)
        }
        val ctx = AVFormatContext(null as Pointer?)
        val ret = avformat_open_input(ctx, url, null, options)
        if (ret < 0) {
            engine.onError(this, "Couldn't open ${source.displayName}: ${errorText(ret)}")
            return false
        }
        fmt = ctx
        avformat_find_stream_info(ctx, null as PointerPointer<*>?).let { if (it < 0) engine.onError(this, "Couldn't read the file: ${errorText(it)}") }
        if (closed) return false

        duration = ctx.duration().takeIf { it != AV_NOPTS_VALUE && it > 0 }?.let { it.toDouble() / AV_TIME_BASE }

        loadAttachedFonts(ctx)

        val video = ArrayList<TrackInfo>()
        val audioTracks = ArrayList<TrackInfo>()
        val subtitles = ArrayList<TrackInfo>()
        var bestVideo: AVStream? = null
        val audioCandidates = ArrayList<Pair<AVStream, TrackInfo>>()
        for (i in 0 until ctx.nb_streams()) {
            val st = ctx.streams(i)
            val par = st.codecpar()
            val meta = st.metadata()
            val language = meta.string("language")
            val title = meta.string("title")
            val disposition = st.disposition()
            val codec = avcodec_get_name(par.codec_id())?.getString(Charsets.UTF_8) ?: "?"
            when (par.codec_type()) {
                AVMEDIA_TYPE_VIDEO -> {
                    if (disposition and AV_DISPOSITION_ATTACHED_PIC != 0) continue // cover art
                    video += TrackInfo("$i", codec, language, title, disposition and AV_DISPOSITION_DEFAULT != 0, detail = "${par.width()}×${par.height()}")
                    if (bestVideo == null || disposition and AV_DISPOSITION_DEFAULT != 0) bestVideo = st
                }
                AVMEDIA_TYPE_AUDIO -> {
                    val ch = par.ch_layout().nb_channels()
                    val layout = when (ch) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$ch ch" }
                    val info = TrackInfo("$i", codec, language, title, disposition and AV_DISPOSITION_DEFAULT != 0, detail = "$layout · ${par.sample_rate() / 1000} kHz")
                    audioTracks += info
                    audioCandidates += st to info
                    st.discard(AVDISCARD_ALL)
                }
                AVMEDIA_TYPE_SUBTITLE -> {
                    val text = codec !in BITMAP_SUBTITLES
                    subtitles += TrackInfo(
                        "$i", codec, language, title,
                        isDefault = disposition and AV_DISPOSITION_DEFAULT != 0,
                        isForced = disposition and AV_DISPOSITION_FORCED != 0,
                        isText = text,
                    )
                    // Text and picture subtitles both decode here; a format with no decoder in this build is dropped.
                    if (!openSubtitleDecoder(st, subtitles.last(), bitmap = !text)) subtitles.removeAt(subtitles.lastIndex)
                }
                else -> st.discard(AVDISCARD_ALL)
            }
        }
        // Non-chosen video streams are skipped by the demuxer.
        for (i in 0 until ctx.nb_streams()) {
            val st = ctx.streams(i)
            if (st.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO && st.index() != bestVideo?.index()) st.discard(AVDISCARD_ALL)
        }

        val chapters = (0 until ctx.nb_chapters()).map { i ->
            val c = ctx.chapters(i)
            val tb = c.time_base().toDouble()
            Chapter(c.start() * tb, c.end() * tb, c.metadata().string("title"))
        }

        // Audio: the preferred language, else the file's default, else the first.
        val pick = audioCandidates.firstOrNull { preferredAudio != null && Languages.same(it.second.language, preferredAudio) }
            ?: audioCandidates.firstOrNull { it.second.isDefault }
            ?: audioCandidates.firstOrNull()
        pick?.let {
            it.first.discard(AVDISCARD_DEFAULT)
            audioStreamIndex = it.first.index()
        }

        videoStream = bestVideo
        bestVideo?.let { st ->
            videoTimeBase = st.time_base().toDouble()
            val rate = st.avg_frame_rate().toDouble().takeIf { it > 1 } ?: st.r_frame_rate().toDouble().takeIf { it > 1 } ?: 30.0
            frameDuration = 1.0 / rate
        }
        wallClock = audioStreamIndex < 0 || audio == null

        engine.onTracks(
            this,
            MediaTracks(video, audioTracks, subtitles, chapters),
            duration,
            audioStreamIndex.takeIf { it >= 0 }?.toString(),
        )
        if (bestVideo == null && audioStreamIndex < 0) {
            engine.onError(this, "${source.displayName} has no video or audio Midas can play.")
            return false
        }
        return true
    }

    private fun demuxLoop() {
        val ctx = fmt!!
        val pkt = av_packet_alloc()
        var eof = false
        var endReported = false
        var lastPublish = 0L
        try {
            while (!closed) {
                pendingSeek.getAndSet(null)?.let { req ->
                    doSeek(ctx, req)
                    eof = false
                    endReported = false
                }
                val now = System.currentTimeMillis()
                if (now - lastPublish > 300) {
                    publishSubtitles()
                    lastPublish = now
                }
                if (eof) {
                    if (!endReported && isFinished()) {
                        endReported = true
                        engine.onEnded(this)
                    }
                    // Audio ran out before the video: keep time on the wall clock instead.
                    val a = audio
                    if (!wallClock && audioEof && a != null && a.bufferedFrames() == 0L) {
                        wallBase = a.position() - engine.audioDelay
                        wallAnchorNanos = System.nanoTime()
                        wallClock = true
                    }
                    Thread.sleep(10)
                    continue
                }
                // Back-pressure: wait while a queue is full, unless the other one is starving
                // (badly interleaved files would otherwise stall both).
                val videoWaiting = videoStream != null && videoPackets.isFull && (audioStreamIndex < 0 || audioPackets.size > 0)
                val audioWaiting = audioStreamIndex >= 0 && audioPackets.isFull && (videoStream == null || videoPackets.size > 0)
                if (videoWaiting || audioWaiting) {
                    Thread.sleep(5)
                    continue
                }
                val ret = av_read_frame(ctx, pkt)
                if (ret < 0) {
                    // EOF, or a network error we can't recover from: drain what we have.
                    val s = serial.get()
                    if (videoStream != null) videoPackets.put(QueueItem.End(s))
                    if (audioStreamIndex >= 0) audioPackets.put(QueueItem.End(s))
                    eof = true
                    if (ret != AVERROR_EOF) System.err.println("Midas: read stopped: ${errorText(ret)}")
                    continue
                }
                val index = pkt.stream_index()
                when {
                    index == videoStream?.index() -> videoPackets.put(QueueItem.Packet(serial.get(), av_packet_clone(pkt)))
                    index == audioStreamIndex -> audioPackets.put(QueueItem.Packet(serial.get(), av_packet_clone(pkt)))
                    else -> subtitleStreams[index]?.let { decodeSubtitle(it, pkt) }
                }
                av_packet_unref(pkt)
            }
        } finally {
            av_packet_free(pkt)
        }
    }

    private fun doSeek(ctx: AVFormatContext, req: SeekRequest) {
        val newAudio = req.audioStream
        if (newAudio != null && newAudio != audioStreamIndex) {
            if (audioStreamIndex >= 0) ctx.streams(audioStreamIndex).discard(AVDISCARD_ALL)
            if (newAudio >= 0) ctx.streams(newAudio).discard(AVDISCARD_DEFAULT)
            audioStreamIndex = newAudio
            wallClock = newAudio < 0 || audio == null
        }
        val ts = (req.target * AV_TIME_BASE).toLong()
        val ret = av_seek_frame(ctx, -1, ts, AVSEEK_FLAG_BACKWARD)
        if (ret < 0) System.err.println("Midas: seek failed: ${errorText(ret)}")
        val s = serial.incrementAndGet()
        videoPackets.clear()
        audioPackets.clear()
        subtitleStreams.values.forEach { avcodec_flush_buffers(it.ctx); it.open.clear() }
        videoEof = false
        audioEof = false
        // Sound always starts at the target; video shows the keyframe at once (when not exact), then catches up.
        videoPackets.put(QueueItem.Flush(s, if (req.exact) req.target - frameDuration / 2 else Double.NaN))
        audioPackets.put(QueueItem.Flush(s, req.target, reopenStream = newAudio))
        wallBase = req.target
        wallAnchorNanos = System.nanoTime()
        if (wallClock) {
            wallClock = audioStreamIndex < 0 || audio == null
        }
    }

    private fun isFinished(): Boolean {
        // Not waiting for the UI to take the frames: a minimized window doesn't draw.
        val videoDone = videoStream == null ||
            (videoEof && synchronized(frameLock) { frames.lastOrNull()?.let { it.pts <= clockNow() } ?: true })
        val a = audio
        val audioDone = audioStreamIndex < 0 || a == null || (audioEof && a.bufferedFrames() == 0L) || wallClock
        if (!videoDone || !audioDone) return false
        // Without audio, wait for the clock to pass the last frame.
        val d = duration
        return !wallClock || d == null || clockNow() >= (current?.pts ?: 0.0) + frameDuration || clockNow() >= d
    }

    // =============================================================================================
    // Subtitles (on the demux thread)
    // =============================================================================================

    /** False when this build has no decoder for the format (the track is then left out). */
    private fun openSubtitleDecoder(st: AVStream, info: TrackInfo, bitmap: Boolean): Boolean {
        val codec = avcodec_find_decoder(st.codecpar().codec_id())
        if (codec == null || codec.isNull) {
            st.discard(AVDISCARD_ALL)
            return false
        }
        val ctx = avcodec_alloc_context3(codec)
        avcodec_parameters_to_context(ctx, st.codecpar())
        ctx.pkt_timebase(st.time_base())
        if (avcodec_open2(ctx, codec, null as PointerPointer<*>?) < 0) {
            avcodec_free_context(ctx)
            st.discard(AVDISCARD_ALL)
            return false
        }
        val header = ctx.subtitle_header()?.takeIf { !it.isNull }?.getString(Charsets.UTF_8).orEmpty()
        val ass = if (info.codec == "ass" || info.codec == "ssa") assLibrary()?.newTrack(extradataOf(st))?.also { r ->
            videoStream?.codecpar()?.let { r.setStorageSize(it.width(), it.height()) }
        } else null
        subtitleStreams[st.index()] = SubtitleStream(
            info.id, ctx, st.time_base().toDouble(), info.label, Languages.normalize(info.language),
            SubtitleParser.assTopStyles(header), bitmap, ass,
        )
        return true
    }

    // ---- libass ----

    @Volatile private var assLibrary: AssLibrary? = null
    private val attachedFonts = ArrayList<Pair<String, ByteArray>>()

    /** This file's libass instance, with its attached fonts; created on first use. */
    @Synchronized
    fun assLibrary(): AssLibrary? {
        assLibrary?.let { return it }
        if (closed) return null
        val lib = AssLibrary.create() ?: return null
        attachedFonts.forEach { (name, data) -> lib.addFont(name, data) }
        lib.setStyle(engine.assScale, engine.assStyle)
        assLibrary = lib
        return lib
    }

    fun assRendererFor(trackId: String): AssTrackRenderer? =
        subtitleStreams.values.firstOrNull { it.id == trackId }?.ass

    fun setAssStyle(scale: Float, style: app.auriel.midas.data.SubtitleStyle) {
        assLibrary?.setStyle(scale, style)
    }

    /** MKV attachments that are fonts (typesetting in fansubs relies on them). */
    private fun loadAttachedFonts(ctx: AVFormatContext) {
        for (i in 0 until ctx.nb_streams()) {
            val st = ctx.streams(i)
            if (st.codecpar().codec_type() != org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_ATTACHMENT) continue
            val name = st.metadata().string("filename") ?: "font$i"
            val mime = st.metadata().string("mimetype").orEmpty()
            val isFont = name.substringAfterLast('.').lowercase() in setOf("ttf", "otf", "ttc", "otc") ||
                "font" in mime || "truetype" in mime || "opentype" in mime
            val data = extradataOf(st)
            if (isFont && data != null) attachedFonts += name to data
        }
    }

    private fun extradataOf(st: AVStream): ByteArray? {
        val size = st.codecpar().extradata_size()
        val ptr = st.codecpar().extradata()
        if (size <= 0 || ptr == null || ptr.isNull) return null
        return ByteArray(size).also { ptr.get(it, 0, size) }
    }

    /** Palette-indexed subtitle picture → BGRA premultiplied. */
    private fun bitmapOf(rect: org.bytedeco.ffmpeg.avcodec.AVSubtitleRect, s: SubtitleStream): SubtitleBitmap? {
        val w = rect.w()
        val h = rect.h()
        if (w <= 0 || h <= 0) return null
        val colors = rect.nb_colors().coerceIn(1, 256)
        val stride = rect.linesize(0)
        val indices = ByteArray(stride * h)
        rect.data(0).get(indices, 0, indices.size)
        // The palette is uint32 0xAARRGGBB, which is B, G, R, A in memory on little-endian machines.
        val palette = IntArray(colors)
        org.bytedeco.javacpp.IntPointer(rect.data(1)).get(palette, 0, colors)
        val bgra = IntArray(colors) { i ->
            val c = palette[i]
            val a = c ushr 24
            val r = (c shr 16 and 0xFF) * a / 255
            val g = (c shr 8 and 0xFF) * a / 255
            val b = (c and 0xFF) * a / 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = ByteArray(w * h * 4)
        var o = 0
        for (y in 0 until h) {
            val row = y * stride
            for (x in 0 until w) {
                val c = bgra[(indices[row + x].toInt() and 0xFF).coerceAtMost(colors - 1)]
                out[o] = c.toByte()
                out[o + 1] = (c shr 8).toByte()
                out[o + 2] = (c shr 16).toByte()
                out[o + 3] = (c ushr 24).toByte()
                o += 4
            }
        }
        // The canvas the positions refer to: the subtitle stream's size (PGS presentation, DVD "size:"), else the video's.
        val cw = s.ctx.width().takeIf { it > 0 } ?: videoStream?.codecpar()?.width()?.takeIf { it > 0 } ?: (rect.x() + w)
        val ch = s.ctx.height().takeIf { it > 0 } ?: videoStream?.codecpar()?.height()?.takeIf { it > 0 } ?: (rect.y() + h)
        return SubtitleBitmap(rect.x(), rect.y(), w, h, out, cw, ch)
    }

    private fun decodeSubtitle(s: SubtitleStream, pkt: AVPacket) {
        s.ass?.let { ass ->
            val ts = if (pkt.pts() != AV_NOPTS_VALUE) pkt.pts() else pkt.dts()
            if (ts != AV_NOPTS_VALUE && pkt.size() > 0) {
                val data = ByteArray(pkt.size())
                pkt.data().get(data, 0, data.size)
                ass.processChunk(data, (ts * s.timeBase * 1000).toLong(), (pkt.duration() * s.timeBase * 1000).toLong())
            }
        }
        val sub = AVSubtitle()
        val got = IntArray(1)
        try {
            if (avcodec_decode_subtitle2(s.ctx, sub, got, pkt) < 0 || got[0] == 0) return
            val pts = (if (pkt.pts() != AV_NOPTS_VALUE) pkt.pts() else pkt.dts()).seconds(s.timeBase)
            if (pts.isNaN()) return
            val start = pts + sub.start_display_time() / 1000.0
            val endMs = sub.end_display_time().toLong() and 0xFFFFFFFFL
            val knownEnd = when {
                endMs in 1 until 0xFFFFFFFFL -> pts + endMs / 1000.0
                pkt.duration() > 0 -> pts + pkt.duration() * s.timeBase
                else -> null
            }
            // Picture subtitles usually have no end time: each display set (even an empty one) ends the last.
            if (s.bitmap) {
                for (k in s.open) {
                    val c = s.cues[k] ?: continue
                    if (start > c.start) { s.cues[k] = c.copy(end = minOf(c.end, start)); s.dirty = true }
                }
                s.open.clear()
            }
            val end = knownEnd ?: (start + if (s.bitmap) 10.0 else 5.0)
            for (r in 0 until sub.num_rects()) {
                val rect = sub.rects(r)
                val cue = when (rect.type()) {
                    SUBTITLE_ASS -> rect.ass()?.takeIf { !it.isNull }?.getString(Charsets.UTF_8)
                        ?.let { SubtitleParser.assEventCue(start, end, it, s.topStyles) }
                    SUBTITLE_TEXT -> rect.text()?.takeIf { !it.isNull }?.getString(Charsets.UTF_8)
                        ?.trim()?.takeIf { it.isNotEmpty() }?.let { SubtitleCue(start, end, it) }
                    SUBTITLE_BITMAP -> bitmapOf(rect, s)?.let { SubtitleCue(start, end, "", bitmap = it) }
                    else -> null
                } ?: continue
                val key = (start * 1000).toLong() to (if (cue.bitmap != null) "#bitmap$r" else cue.text)
                if (key !in s.cues) {
                    s.cues[key] = cue
                    s.dirty = true
                    if (cue.bitmap != null && knownEnd == null) s.open += key
                }
            }
        } finally {
            avsubtitle_free(sub)
            sub.close()
        }
    }

    private fun publishSubtitles() {
        val dirty = subtitleStreams.values.filter { it.dirty }
        if (dirty.isEmpty()) return
        engine.onSubtitles(this, dirty.associate { s ->
            s.dirty = false
            s.id to SubtitleTrack(s.label, s.language, s.cues.values.toList())
        })
    }

    // =============================================================================================
    // Video thread
    // =============================================================================================

    private class VideoDecoder(val ctx: AVCodecContext, val hwDevice: AVBufferRef?, val hwPixFmt: Int) {
        fun close() {
            avcodec_free_context(ctx)
            hwDevice?.let { av_buffer_unref(it) }
        }
    }

    private fun openVideoDecoder(st: AVStream, hardware: Boolean): VideoDecoder? {
        val codec = avcodec_find_decoder(st.codecpar().codec_id()) ?: return null
        val ctx = avcodec_alloc_context3(codec)
        avcodec_parameters_to_context(ctx, st.codecpar())
        ctx.pkt_timebase(st.time_base())
        var device: AVBufferRef? = null
        var hwFmt = AV_PIX_FMT_NONE
        val type = if (hardware) preferredDeviceType() else AV_HWDEVICE_TYPE_NONE
        if (type != AV_HWDEVICE_TYPE_NONE) {
            var i = 0
            while (true) {
                val config = avcodec_get_hw_config(codec, i++) ?: break
                if (config.isNull) break
                if (config.methods() and AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX != 0 && config.device_type() == type) {
                    hwFmt = config.pix_fmt()
                    break
                }
            }
            if (hwFmt != AV_PIX_FMT_NONE) {
                val ref = AVBufferRef(null as Pointer?)
                if (av_hwdevice_ctx_create(ref, type, null as String?, null, 0) >= 0) {
                    device = ref
                    // The default get_format picks the hardware format when a device is attached.
                    ctx.hw_device_ctx(av_buffer_ref(ref))
                } else {
                    hwFmt = AV_PIX_FMT_NONE
                }
            }
        }
        // Frame threading for software decoding; hardware decoders work best on one thread.
        ctx.thread_count(if (device != null) 1 else 0)
        if (avcodec_open2(ctx, codec, null as PointerPointer<*>?) < 0) {
            avcodec_free_context(ctx)
            device?.let { av_buffer_unref(it) }
            return if (hardware) openVideoDecoder(st, hardware = false) else null
        }
        engine.onVideoDecoder(this, if (device != null) "Hardware (${hwName(type)})" else "Software")
        return VideoDecoder(ctx, device, hwFmt)
    }

    private fun runVideo(st: AVStream) {
        var decoder = openVideoDecoder(st, hardwareDecoding) ?: run {
            engine.onError(this, "No decoder for this video format.")
            return
        }
        val frame = av_frame_alloc()
        val swFrame = av_frame_alloc()
        var bgra: AVFrame? = null
        var sws: SwsContext? = null
        var swsKey: List<Int> = emptyList()
        var discardBefore = Double.NaN
        var mySerial = serial.get()

        fun emit(src: AVFrame) {
            val pts = src.best_effort_timestamp().let { if (it == AV_NOPTS_VALUE) src.pts() else it }.seconds(videoTimeBase)
            if (pts.isNaN()) return
            if (!discardBefore.isNaN() && pts < discardBefore) return
            // Late and not the first frame after a seek: skip the conversion to catch up.
            if (!showNextImmediately && seekTarget == null && pts < clockNow() - 0.08 && playing) return

            var input = src
            if (decoder.hwPixFmt != AV_PIX_FMT_NONE && src.format() == decoder.hwPixFmt) {
                av_frame_unref(swFrame)
                if (av_hwframe_transfer_data(swFrame, src, 0) < 0) return
                swFrame.pts(src.pts())
                input = swFrame
            }
            val w = input.width()
            val h = input.height()
            if (w <= 0 || h <= 0) return
            val key = listOf(w, h, input.format(), input.colorspace(), input.color_range())
            if (key != swsKey) {
                sws = sws_getCachedContext(sws, w, h, input.format(), w, h, AV_PIX_FMT_BGRA, SWS_BILINEAR, null, null, null as DoublePointer?)
                val space = when (input.colorspace()) {
                    AVCOL_SPC_BT709 -> SWS_CS_ITU709
                    AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> SWS_CS_BT2020
                    AVCOL_SPC_SMPTE170M, AVCOL_SPC_BT470BG -> SWS_CS_ITU601
                    else -> if (h >= 720) SWS_CS_ITU709 else SWS_CS_DEFAULT
                }
                sws_setColorspaceDetails(
                    sws, sws_getCoefficients(space), if (input.color_range() == AVCOL_RANGE_JPEG) 1 else 0,
                    sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 shl 16, 1 shl 16,
                )
                bgra?.let { av_frame_free(it) }
                bgra = av_frame_alloc().also {
                    it.format(AV_PIX_FMT_BGRA)
                    it.width(w)
                    it.height(h)
                    av_frame_get_buffer(it, 64)
                }
                swsKey = key
            }
            val out = bgra!!
            sws_scale(sws, input.data(), input.linesize(), 0, h, out.data(), out.linesize())
            val stride = out.linesize(0)
            val pixels = takeBuffer(stride * h)
            out.data(0).get(pixels, 0, pixels.size)
            val sar = input.sample_aspect_ratio().toDouble().takeIf { it > 0 } ?: 1.0
            val vf = VideoFrame(w, h, stride, pixels, pts, sar)

            // Wait for room: a few frames ahead is plenty.
            while (!closed && serial.get() == mySerial) {
                val full = synchronized(frameLock) {
                    // Frames whose time has passed and nobody took (window hidden): drop them so decoding moves on.
                    val head = frames.firstOrNull()
                    if (frames.size >= 4 && head != null && head.pts < clockNow() - frameDuration) {
                        frames.removeFirst()
                        if (pool.size < 6) pool.addLast(head.pixels)
                    }
                    frames.size >= 4
                }
                if (!full) break
                Thread.sleep(2)
            }
            if (closed || serial.get() != mySerial) return
            synchronized(frameLock) { frames.addLast(vf) }
            if (seekTarget != null && (wallClock || audioStreamIndex < 0)) {
                wallBase = maxOf(pts, seekTarget ?: pts)
                wallAnchorNanos = System.nanoTime()
                seekTarget = null
            }
        }

        fun receiveAll() {
            while (!closed) {
                val r = avcodec_receive_frame(decoder.ctx, frame)
                if (r < 0) break
                // The GPU couldn't take this stream (e.g. 10-bit on an older card): decoding continues in
                // software, but single-threaded. Reopen as a threaded software decoder.
                if (decoder.hwPixFmt != AV_PIX_FMT_NONE && frame.format() != decoder.hwPixFmt) {
                    av_frame_unref(frame)
                    decoder.close()
                    decoder = openVideoDecoder(st, hardware = false) ?: return
                    seek(clockNow(), exact = true)
                    return
                }
                emit(frame)
                av_frame_unref(frame)
            }
        }

        try {
            while (!closed) {
                val item = videoPackets.poll(10) ?: continue
                when (item) {
                    is QueueItem.Flush -> {
                        avcodec_flush_buffers(decoder.ctx)
                        mySerial = item.serial
                        discardBefore = item.discardBefore
                        videoEof = false
                        synchronized(frameLock) {
                            frames.forEach { pool.addLast(it.pixels) }
                            frames.clear()
                            while (pool.size > 6) pool.removeFirst()
                            showNextImmediately = true
                        }
                    }
                    is QueueItem.Packet -> {
                        if (item.serial == mySerial) {
                            avcodec_send_packet(decoder.ctx, item.packet)
                            receiveAll()
                        }
                        av_packet_free(item.packet)
                    }
                    is QueueItem.End -> if (item.serial == mySerial) {
                        avcodec_send_packet(decoder.ctx, null as AVPacket?)
                        receiveAll()
                        videoEof = true
                    }
                }
                // Once frames at the target arrive, stop discarding (also covers files whose pts jump).
                if (!discardBefore.isNaN() && synchronized(frameLock) { frames.isNotEmpty() }) discardBefore = Double.NaN
            }
        } finally {
            av_frame_free(frame)
            av_frame_free(swFrame)
            bgra?.let { av_frame_free(it) }
            sws?.let { sws_freeContext(it) }
            decoder.close()
        }
    }

    // =============================================================================================
    // Audio thread
    // =============================================================================================

    private inner class AudioDecoder(val streamIndex: Int) {
        val st: AVStream = fmt!!.streams(streamIndex)
        val timeBase = st.time_base().toDouble()
        val ctx: AVCodecContext
        var graph: AVFilterGraph? = null
        var src: AVFilterContext? = null
        var sink: AVFilterContext? = null
        var graphKey: List<Any> = emptyList()

        init {
            val codec = avcodec_find_decoder(st.codecpar().codec_id()) ?: error("No decoder for this audio format")
            ctx = avcodec_alloc_context3(codec)
            avcodec_parameters_to_context(ctx, st.codecpar())
            ctx.pkt_timebase(st.time_base())
            val r = avcodec_open2(ctx, codec, null as PointerPointer<*>?)
            if (r < 0) {
                avcodec_free_context(ctx)
                error("Couldn't open the audio decoder: ${errorText(r)}")
            }
        }

        /** abuffer → atempo (speed) → 48 kHz stereo s16 → sink. Rebuilt when the input format or speed changes. */
        fun ensureGraph(f: AVFrame, speed: Double) {
            val layout = f.ch_layout()
            if (layout.order() == AV_CHANNEL_ORDER_UNSPEC) av_channel_layout_default(layout, layout.nb_channels())
            val desc = BytePointer(128)
            av_channel_layout_describe(layout, desc, 128)
            val layoutName = desc.getString(Charsets.UTF_8)
            desc.close()
            val key = listOf(f.format(), f.sample_rate(), layoutName, speed)
            if (key == graphKey && graph != null) return
            freeGraph()
            val g = avfilter_graph_alloc()
            val srcCtx = AVFilterContext(null as Pointer?)
            val sinkCtx = AVFilterContext(null as Pointer?)
            val sampleFmt = av_get_sample_fmt_name(f.format()).getString(Charsets.UTF_8)
            val args = "time_base=1/${f.sample_rate()}:sample_rate=${f.sample_rate()}:sample_fmt=$sampleFmt:channel_layout=$layoutName"
            check(avfilter_graph_create_filter(srcCtx, avfilter_get_by_name("abuffer"), "in", args, null, g) >= 0) { "abuffer: $args" }
            check(avfilter_graph_create_filter(sinkCtx, avfilter_get_by_name("abuffersink"), "out", null as String?, null, g) >= 0) { "abuffersink" }
            val outputs = avfilter_inout_alloc()
            outputs.name(av_strdup(BytePointer("in")))
            outputs.filter_ctx(srcCtx)
            outputs.pad_idx(0)
            outputs.next(null)
            val inputs = avfilter_inout_alloc()
            inputs.name(av_strdup(BytePointer("out")))
            inputs.filter_ctx(sinkCtx)
            inputs.pad_idx(0)
            inputs.next(null)
            val chain = buildString {
                append(tempoChain(speed))
                append("aresample=${AudioOutput.SAMPLE_RATE},aformat=sample_fmts=s16:sample_rates=${AudioOutput.SAMPLE_RATE}:channel_layouts=stereo")
            }
            val parsed = avfilter_graph_parse_ptr(g, chain, inputs, outputs, null)
            avfilter_inout_free(inputs)
            avfilter_inout_free(outputs)
            check(parsed >= 0 && avfilter_graph_config(g, null) >= 0) { "Audio filter setup failed" }
            graph = g
            src = srcCtx
            sink = sinkCtx
            graphKey = key
        }

        fun freeGraph() {
            graph?.let { avfilter_graph_free(it) }
            graph = null
            src = null
            sink = null
            graphKey = emptyList()
        }

        fun close() {
            freeGraph()
            avcodec_free_context(ctx)
        }
    }

    private fun runAudio() {
        val out = audio ?: return
        var decoder: AudioDecoder? = null
        val frame = av_frame_alloc()
        val filtered = av_frame_alloc()
        var discardBefore = Double.NaN
        var mySerial = serial.get()
        var buffer = ByteArray(16 * 1024)
        var anchorPending = true
        var segmentPts = Double.NaN

        fun openDecoder(index: Int) {
            decoder?.close()
            decoder = if (index >= 0) runCatching { AudioDecoder(index) }
                .onFailure { System.err.println("Midas: ${it.message}") }
                .getOrNull() else null
            // No usable audio decoder: keep time on the wall clock so video still plays.
            if (decoder == null) {
                wallClock = true
                seekTarget = null
            }
        }

        /** Writes filtered PCM, waiting for room in the line; gives up on a seek or close. */
        fun writeOut(bytes: ByteArray, length: Int) {
            val gain = engine.volume.let { it * it }
            if (gain < 0.999f) {
                var i = 0
                while (i + 1 < length) {
                    val s = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
                    val v = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[i] = v.toByte()
                    bytes[i + 1] = (v shr 8).toByte()
                    i += 2
                }
            }
            var off = 0
            while (off < length && !closed && serial.get() == mySerial) {
                val free = out.freeBytes()
                if (free < AudioOutput.BYTES_PER_FRAME) {
                    Thread.sleep(3)
                    continue
                }
                off += out.write(bytes, off, min(free, length - off))
            }
        }

        fun drainFilter(d: AudioDecoder) {
            val sink = d.sink ?: return
            while (!closed && serial.get() == mySerial) {
                if (av_buffersink_get_frame(sink, filtered) < 0) break
                val len = filtered.nb_samples() * AudioOutput.BYTES_PER_FRAME
                if (buffer.size < len) buffer = ByteArray(len)
                filtered.data(0).get(buffer, 0, len)
                av_frame_unref(filtered)
                if (anchorPending && !segmentPts.isNaN()) {
                    out.anchor(segmentPts, engine.speed.toDouble())
                    anchorPending = false
                    seekTarget = null
                }
                writeOut(buffer, len)
            }
        }

        fun process(d: AudioDecoder, f: AVFrame) {
            val pts = f.best_effort_timestamp().let { if (it == AV_NOPTS_VALUE) f.pts() else it }.seconds(d.timeBase)
            val length = f.nb_samples().toDouble() / f.sample_rate().coerceAtLeast(1)
            if (!discardBefore.isNaN() && !pts.isNaN() && pts + length < discardBefore) return
            if (anchorPending && segmentPts.isNaN()) segmentPts = if (pts.isNaN()) (discardBefore.takeUnless { it.isNaN() } ?: 0.0) else pts
            d.ensureGraph(f, engine.speed.toDouble())
            if (av_buffersrc_add_frame(d.src, f) < 0) return
            drainFilter(d)
        }

        fun receiveAll(d: AudioDecoder) {
            while (!closed && serial.get() == mySerial) {
                if (avcodec_receive_frame(d.ctx, frame) < 0) break
                process(d, frame)
                av_frame_unref(frame)
            }
        }

        try {
            if (audioStreamIndex >= 0) openDecoder(audioStreamIndex)
            while (!closed) {
                val item = audioPackets.poll(10) ?: continue
                val d = decoder
                when (item) {
                    is QueueItem.Flush -> {
                        mySerial = item.serial
                        item.reopenStream?.let { openDecoder(it) }
                        decoder?.let {
                            avcodec_flush_buffers(it.ctx)
                            it.freeGraph()
                        }
                        out.flush()
                        if (playing) out.start()
                        discardBefore = item.discardBefore
                        anchorPending = true
                        segmentPts = Double.NaN
                        audioEof = false
                    }
                    is QueueItem.Packet -> {
                        if (item.serial == mySerial && d != null) {
                            avcodec_send_packet(d.ctx, item.packet)
                            receiveAll(d)
                        }
                        av_packet_free(item.packet)
                    }
                    is QueueItem.End -> if (item.serial == mySerial) {
                        if (d != null) {
                            avcodec_send_packet(d.ctx, null as AVPacket?)
                            receiveAll(d)
                            d.src?.let { av_buffersrc_add_frame(it, null as AVFrame?); drainFilter(d) }
                        }
                        audioEof = true
                    }
                }
            }
        } finally {
            av_frame_free(frame)
            av_frame_free(filtered)
            decoder?.close()
        }
    }

    companion object {
        private val BITMAP_SUBTITLES = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub", "dvb_teletext")

        private fun preferredDeviceType(): Int {
            val os = System.getProperty("os.name").lowercase()
            return when {
                "win" in os -> AV_HWDEVICE_TYPE_D3D11VA
                "mac" in os || "darwin" in os -> AV_HWDEVICE_TYPE_VIDEOTOOLBOX
                else -> AV_HWDEVICE_TYPE_VAAPI
            }
        }

        private fun hwName(type: Int) = when (type) {
            AV_HWDEVICE_TYPE_D3D11VA -> "D3D11VA"
            AV_HWDEVICE_TYPE_VIDEOTOOLBOX -> "VideoToolbox"
            AV_HWDEVICE_TYPE_VAAPI -> "VAAPI"
            else -> "GPU"
        }

        /** atempo takes 0.5–100 per instance; chain two for 0.25×. */
        private fun tempoChain(speed: Double): String = when {
            abs(speed - 1.0) < 0.001 -> ""
            speed < 0.5 -> "atempo=0.5,atempo=${speed / 0.5},"
            else -> "atempo=$speed,"
        }

        private fun thread(name: String, block: () -> Unit): Thread =
            Thread(block, name).apply {
                isDaemon = true
                priority = if (name == "midas-audio") Thread.MAX_PRIORITY else Thread.NORM_PRIORITY + 1
                start()
            }
    }
}
