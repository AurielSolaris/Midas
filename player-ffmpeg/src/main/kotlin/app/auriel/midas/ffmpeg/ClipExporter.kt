package app.auriel.midas.ffmpeg

import app.auriel.midas.core.MediaSource
import app.auriel.midas.data.CodecMode
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.FF_COMPLIANCE_NORMAL
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_query_codec
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.swscale.SWS_BICUBIC
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import kotlin.math.roundToLong

/**
 * Cuts [start, end] out of a video into an H.264 MP4. Video is re-encoded (frame-exact cut) with the
 * GPU's encoder or OpenH264; the audio track is copied as-is when MP4 can hold it.
 */
object ClipExporter {
    class Result(val file: File, val encoder: String, val audioCopied: Boolean)

    /** Encoder names to try, best first. Missing or unusable ones (no such GPU) are skipped. */
    fun encoderCandidates(mode: CodecMode): List<String> {
        val os = System.getProperty("os.name").lowercase()
        val hardware = when {
            "win" in os -> listOf("h264_nvenc", "h264_qsv", "h264_amf", "h264_mf")
            "mac" in os || "darwin" in os -> listOf("h264_videotoolbox")
            else -> listOf("h264_nvenc", "h264_qsv")
        }
        val software = listOf("libopenh264", "mpeg4")
        return if (mode == CodecMode.Hardware) hardware + software else software
    }

    fun encoderLabel(name: String): String = when (name) {
        "h264_nvenc" -> "Hardware (NVIDIA NVENC)"
        "h264_qsv" -> "Hardware (Intel Quick Sync)"
        "h264_amf" -> "Hardware (AMD AMF)"
        "h264_mf" -> "Hardware (Media Foundation)"
        "h264_videotoolbox" -> "Hardware (VideoToolbox)"
        "libopenh264" -> "Software (OpenH264)"
        else -> "Software (MPEG-4)"
    }

    /**
     * Blocking; call off the UI thread. [audioStream] is the input stream index to keep (-1 = none).
     * [progress] gets 0..1. Throws with a readable message on failure.
     */
    fun export(
        source: MediaSource,
        start: Double,
        end: Double,
        out: File,
        mode: CodecMode,
        audioStream: Int,
        progress: (Float) -> Unit = {},
    ): Result {
        FfmpegNatives.load()
        require(end > start) { "The clip's end must be after its start" }
        val url = when (source) {
            is MediaSource.File -> source.path
            is MediaSource.Http -> source.url
            is MediaSource.ContentUri -> source.uri
        }
        val inOpts = AVDictionary(null as Pointer?)
        if (source is MediaSource.Http && source.headers.isNotEmpty()) {
            av_dict_set(inOpts, "headers", source.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }, 0)
        }
        val input = AVFormatContext(null as Pointer?)
        avformat_open_input(input, url, null, inOpts).let { if (it < 0) error("Couldn't open the video: ${errorText(it)}") }
        av_dict_free(inOpts)

        var decoder: AVCodecContext? = null
        var encoder: AVCodecContext? = null
        var output: AVFormatContext? = null
        var sws: SwsContext? = null
        val frame = av_frame_alloc()
        var converted: AVFrame? = null
        val pkt = av_packet_alloc()
        val outPkt = av_packet_alloc()
        out.parentFile?.mkdirs()
        try {
            avformat_find_stream_info(input, null as PointerPointer<*>?)
            val vIndex = (0 until input.nb_streams()).firstOrNull {
                val st = input.streams(it)
                st.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO && st.disposition() and AV_DISPOSITION_ATTACHED_PIC == 0
            } ?: error("This file has no video to export")
            val vStream = input.streams(vIndex)
            val aStream = audioStream.takeIf { it >= 0 && it < input.nb_streams() }?.let { input.streams(it) }
                ?.takeIf { it.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO }

            // ---- Decoder (CPU, threaded: export doesn't need to keep up with real time) ----
            val dCodec = avcodec_find_decoder(vStream.codecpar().codec_id()) ?: error("No decoder for this video")
            decoder = avcodec_alloc_context3(dCodec).also {
                avcodec_parameters_to_context(it, vStream.codecpar())
                it.pkt_timebase(vStream.time_base())
                it.thread_count(0)
                if (avcodec_open2(it, dCodec, null as PointerPointer<*>?) < 0) error("Couldn't open the video decoder")
            }

            // ---- Output ----
            val o = AVFormatContext(null as Pointer?)
            avformat_alloc_output_context2(o, null, "mp4", out.path).let { if (it < 0) error("Couldn't create ${out.name}: ${errorText(it)}") }
            output = o
            val outVideo = avformat_new_stream(o, null)
            val copyAudio = aStream != null && avformat_query_codec(o.oformat(), aStream.codecpar().codec_id(), FF_COMPLIANCE_NORMAL) == 1
            val outAudio: AVStream? = if (copyAudio) avformat_new_stream(o, null).also {
                avcodec_parameters_copy(it.codecpar(), aStream!!.codecpar())
                it.codecpar().codec_tag(0)
                it.time_base(aStream.time_base())
            } else null

            val startTs = (start * AV_TIME_BASE).roundToLong()
            av_seek_frame(input, -1, startTs, AVSEEK_FLAG_BACKWARD)
            val vtb = vStream.time_base()
            val startV = (start / vtb.toDouble()).roundToLong()
            val endV = (end / vtb.toDouble()).roundToLong()
            var encoderName = ""
            var headerWritten = false
            val pendingAudio = ArrayList<AVPacket>()

            fun writeEncoded(enc: AVCodecContext) {
                while (avcodec_receive_packet(enc, outPkt) >= 0) {
                    outPkt.stream_index(outVideo.index())
                    av_packet_rescale_ts(outPkt, enc.time_base(), outVideo.time_base())
                    av_interleaved_write_frame(o, outPkt)
                    av_packet_unref(outPkt)
                }
            }

            fun openEncoder(w: Int, h: Int): AVCodecContext {
                val errors = ArrayList<String>()
                for (name in encoderCandidates(mode)) {
                    val codec: AVCodec = avcodec_find_encoder_by_name(name)?.takeIf { !it.isNull } ?: continue
                    val enc = avcodec_alloc_context3(codec)
                    val hw = !name.startsWith("lib") && name != "mpeg4"
                    enc.width(w)
                    enc.height(h)
                    enc.pix_fmt(if (hw) AV_PIX_FMT_NV12 else AV_PIX_FMT_YUV420P)
                    enc.time_base(vtb)
                    val rate = vStream.avg_frame_rate().takeIf { it.num() > 0 && it.den() > 0 } ?: AVRational().num(30).den(1)
                    enc.framerate(rate)
                    enc.sample_aspect_ratio(vStream.codecpar().sample_aspect_ratio())
                    // ~0.1 bit per pixel per frame: near-transparent for 1080p at ~6 Mbit/s.
                    enc.bit_rate((w.toLong() * h * rate.toDouble() * 0.1).toLong().coerceIn(1_500_000, 40_000_000))
                    enc.gop_size((rate.toDouble() * 2).toInt().coerceAtLeast(12))
                    enc.max_b_frames(if (name == "h264_mf" || name == "libopenh264") 0 else 2)
                    if (o.oformat().flags() and AVFMT_GLOBALHEADER != 0) enc.flags(enc.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
                    val opts = AVDictionary(null as Pointer?)
                    when (name) {
                        "h264_nvenc" -> av_dict_set(opts, "preset", "p5", 0)
                        "h264_qsv" -> av_dict_set(opts, "preset", "medium", 0)
                        "h264_mf" -> av_dict_set(opts, "hw_encoding", "1", 0)
                    }
                    val r = avcodec_open2(enc, codec, opts)
                    av_dict_free(opts)
                    if (r >= 0) {
                        encoderName = name
                        return enc
                    }
                    errors += "$name: ${errorText(r)}"
                    avcodec_free_context(enc)
                }
                error("No video encoder worked (${errors.joinToString("; ")})")
            }

            fun encodeFrame(src: AVFrame, pts: Long) {
                val w = src.width() and 1.inv() // H.264 wants even dimensions
                val h = src.height() and 1.inv()
                val enc = encoder ?: openEncoder(w, h).also { e ->
                    encoder = e
                    avcodec_parameters_from_context(outVideo.codecpar(), e)
                    outVideo.time_base(e.time_base())
                    if (o.oformat().flags() and AVFMT_NOFILE == 0) {
                        val pb = AVIOContext(null as Pointer?)
                        avio_open(pb, out.path, AVIO_FLAG_WRITE).let { if (it < 0) error("Couldn't write ${out.path}: ${errorText(it)}") }
                        o.pb(pb)
                    }
                    avformat_write_header(o, null as PointerPointer<*>?).let { if (it < 0) error("Couldn't write the MP4 header: ${errorText(it)}") }
                    headerWritten = true
                    pendingAudio.forEach { a -> av_interleaved_write_frame(o, a); av_packet_free(a) }
                    pendingAudio.clear()
                }
                val conv = converted ?: av_frame_alloc().also {
                    it.format(enc.pix_fmt()); it.width(enc.width()); it.height(enc.height())
                    av_frame_get_buffer(it, 64)
                    converted = it
                }
                sws = sws_getCachedContext(sws, src.width(), src.height(), src.format(), enc.width(), enc.height(), enc.pix_fmt(), SWS_BICUBIC, null, null, null as DoublePointer?)
                sws_scale(sws, src.data(), src.linesize(), 0, src.height(), conv.data(), conv.linesize())
                conv.pts(pts)
                avcodec_send_frame(enc, conv)
                writeEncoded(enc)
            }

            var done = false
            while (!done) {
                val r = av_read_frame(input, pkt)
                if (r < 0) {
                    avcodec_send_packet(decoder, null as AVPacket?)
                    done = true
                } else if (pkt.stream_index() == vIndex) {
                    avcodec_send_packet(decoder, pkt)
                } else if (outAudio != null && pkt.stream_index() == aStream!!.index()) {
                    val atb = aStream.time_base()
                    val t = if (pkt.pts() == AV_NOPTS_VALUE) Double.NaN else pkt.pts() * atb.toDouble()
                    if (!t.isNaN() && t >= start && t < end) {
                        val shift = (start / atb.toDouble()).roundToLong()
                        val copy = org.bytedeco.ffmpeg.global.avcodec.av_packet_clone(pkt)
                        copy.pts(copy.pts() - shift)
                        if (copy.dts() != AV_NOPTS_VALUE) copy.dts(copy.dts() - shift)
                        copy.stream_index(outAudio.index())
                        av_packet_rescale_ts(copy, atb, outAudio.time_base())
                        copy.pos(-1)
                        if (headerWritten) { av_interleaved_write_frame(o, copy); av_packet_free(copy) } else pendingAudio += copy
                    }
                    av_packet_unref(pkt)
                    continue
                } else {
                    av_packet_unref(pkt)
                    continue
                }
                av_packet_unref(pkt)
                while (avcodec_receive_frame(decoder, frame) >= 0) {
                    val pts = frame.best_effort_timestamp().let { if (it == AV_NOPTS_VALUE) frame.pts() else it }
                    if (pts != AV_NOPTS_VALUE && pts >= startV && pts < endV) {
                        encodeFrame(frame, pts - startV)
                        progress(((pts - startV).toFloat() / (endV - startV)).coerceIn(0f, 1f))
                    }
                    av_frame_unref(frame)
                    if (pts != AV_NOPTS_VALUE && pts >= endV) done = true
                }
            }
            val enc = encoder ?: error("No frames between ${start}s and ${end}s")
            avcodec_send_frame(enc, null as AVFrame?)
            writeEncoded(enc)
            av_write_trailer(o)
            progress(1f)
            return Result(out, encoderLabel(encoderName), copyAudio)
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        } finally {
            av_packet_free(pkt)
            av_packet_free(outPkt)
            av_frame_free(frame)
            converted?.let { av_frame_free(it) }
            sws?.let { sws_freeContext(it) }
            decoder?.let { avcodec_free_context(it) }
            encoder?.let { avcodec_free_context(it) }
            output?.let { o ->
                if (o.oformat().flags() and AVFMT_NOFILE == 0 && o.pb() != null && !o.pb().isNull) avio_closep(o.pb())
                avformat_free_context(o)
            }
            avformat_close_input(input)
        }
    }
}
