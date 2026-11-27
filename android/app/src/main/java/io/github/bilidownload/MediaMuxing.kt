package io.github.bilidownload

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal fun isCompleteMp4(file: File): Boolean = runCatching {
    RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        var position = 0L
        var movie = false
        var media = false
        repeat(256) {
            if (position == length) return@use movie && media
            if (length - position < 8) return@use false
            input.seek(position)
            var size = input.readInt().toLong() and 0xFFFFFFFFL
            val type = input.readInt()
            if (position == 0L && type != 0x66747970 /* ftyp */) return@use false
            var header = 8
            when (size) {
                0L -> size = length - position
                1L -> { size = input.readLong(); header = 16 }
            }
            if (size < header || size > length - position) return@use false
            when (type) {
                0x6d6f6f66 /* moof */ -> return@use false
                0x6d6f6f76 /* moov */ -> movie = size > header
                0x6d646174 /* mdat */ -> media = size > header
            }
            position += size
        }
        false
    }
}.getOrDefault(false)

internal fun canSaveMp4Directly(file: File, audioOnly: Boolean = false): Boolean {
    if (!isCompleteMp4(file)) return false
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        val aac = MediaFormat.MIMETYPE_AUDIO_AAC
        val avc = MediaFormat.MIMETYPE_VIDEO_AVC
        val compatible = if (audioOnly) types == listOf(aac) else avc in types && types.size <= 2 &&
            types.count { it == avc } == 1 && types.all { it == aac || it == avc }
        compatible && extractor.drmInitData == null && (0 until extractor.trackCount).all { track ->
            extractor.selectTrack(track)
            val readable = extractor.sampleTrackIndex == track && extractor.sampleSize > 0 &&
                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0
            extractor.unselectTrack(track)
            readable
        }
    } catch (_: Exception) { false }
    finally { extractor.release() }
}

internal suspend fun muxMedia(videos: List<File>, audio: File?, muxer: MediaMuxer, audioOnly: Boolean = false) {
    var started = false
    fun selectedTracks(extractor: MediaExtractor, file: File): Map<Int, String> {
        val tracks = mutableMapOf<Int, String>()
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            val kind = when {
                mime.startsWith("video/") && !audioOnly && file != audio -> "video"
                mime.startsWith("audio/") && (audio == null || file == audio) -> "audio"
                else -> continue
            }
            if (kind !in tracks.values) tracks[index] = kind
        }
        return tracks
    }
    try {
        val coroutine = currentCoroutineContext()
        val tracks = mutableMapOf<String, Int>()
        for (file in listOfNotNull(videos.firstOrNull(), audio)) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                for ((index, kind) in selectedTracks(extractor, file))
                    tracks[kind] = muxer.addTrack(extractor.getTrackFormat(index))
            } finally { extractor.release() }
        }
        val required = if (audioOnly) "audio" else "video"
        if (required !in tracks)
            throw BiliException(if (audioOnly) "媒体源中没有可导出的音轨。" else "媒体源中没有可导出的视频轨道。")
        muxer.start(); started = true
        val offsets = mutableMapOf<String, Long>()
        var buffer = ByteBuffer.allocateDirect(512 * 1024)
        val info = MediaCodec.BufferInfo()
        for (file in videos + listOfNotNull(audio)) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val selected = selectedTracks(extractor, file).filterValues { it in tracks }
                val durations = mutableMapOf<String, Long>()
                val steps = mutableMapOf<String, Long>()
                for ((index, kind) in selected) {
                    val format = extractor.getTrackFormat(index)
                    durations[kind] = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
                    val rate = if (kind == "video") format.getInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        else format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 48_000)
                    steps[kind] = (if (kind == "video") 1_000_000L else 1_024_000_000L) / rate.coerceAtLeast(1)
                    extractor.selectTrack(index)
                }
                val first = mutableMapOf<String, Long>()
                val ends = mutableMapOf<String, Long>()
                while (true) {
                    coroutine.ensureActive()
                    val kind = selected[extractor.sampleTrackIndex] ?: break
                    val flags = extractor.sampleFlags
                    if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw BiliException("加密媒体轨道无法导出。")
                    val requiredBytes = extractor.sampleSize
                    if (requiredBytes > buffer.capacity()) {
                        if (requiredBytes > 64 * 1024 * 1024) throw BiliException("媒体样本过大，无法合并。")
                        buffer = ByteBuffer.allocateDirect(maxOf(buffer.capacity() * 2, requiredBytes.toInt()))
                    }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sourceTime = extractor.sampleTime
                    val start = first.getOrPut(kind) { sourceTime }
                    val time = sourceTime - start + offsets.getOrDefault(kind, 0)
                    val keyframe = if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, time, keyframe)
                    muxer.writeSampleData(tracks.getValue(kind), buffer, info)
                    ends[kind] = maxOf(ends.getOrDefault(kind, 0), time + steps.getValue(kind))
                    extractor.advance()
                }
                ends.forEach { (kind, end) ->
                    offsets[kind] = maxOf(end, offsets.getOrDefault(kind, 0) + durations.getOrDefault(kind, 0))
                }
            } finally { extractor.release() }
        }
        muxer.stop(); started = false
    } finally {
        if (started) runCatching { muxer.stop() }
        muxer.release()
    }
}
