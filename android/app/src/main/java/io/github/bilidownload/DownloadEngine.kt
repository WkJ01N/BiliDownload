package io.github.bilidownload

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class DownloadEngine(private val context: Context) {
    private val limit = Semaphore(2)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val running = ConcurrentHashMap<String, Job>()
    suspend fun pause(id: String, canceled: Boolean = false) {
        running[id]?.cancelAndJoin()
        BiliApplication.store.update(id) { if (it.state == "Completed") it else it.copy(state = if(canceled) "Canceled" else "Paused", message = if(canceled) "已取消" else "已暂停") }
        if(canceled && BiliApplication.store.get(id)?.state == "Canceled") File(context.cacheDir, "downloads/$id").deleteRecursively()
    }
    suspend fun run(id: String, notify: (DownloadEntry) -> Unit) = limit.withPermit {
        locks.getOrPut(id) { Mutex() }.withLock {
            val job = currentCoroutineContext().job; running[id] = job
            fun update(state: String = "Downloading", progress: Float? = null, message: String) {
                BiliApplication.store.update(id) { it.copy(state = state, progress = progress ?: it.progress, message = message) }
                BiliApplication.store.get(id)?.let(notify)
            }
            val entry = BiliApplication.store.get(id) ?: return@withLock
            if (entry.state != "Queued") { running.remove(id, job); return@withLock }
            val directory = File(context.cacheDir, "downloads/$id").apply { mkdirs() }
            try {
                currentCoroutineContext().ensureActive(); update(message = "获取媒体源")
                var videos = emptyList<File>(); var audio: File? = null
                for (attempt in 0..2) {
                    try {
                        val plan = BiliApplication.api.plan(entry.item, entry.quality)
                        val parts = plan.video + listOfNotNull(plan.audio)
                        val files = mutableListOf<File>()
                        for ((index, part) in parts.withIndex()) {
                            val file = File(directory, Links.safeName(part.key) + ".part")
                            transfer(part, file) { fraction -> update(progress = (index + fraction) / parts.size * .95f, message = "正在下载 · ${plan.variant.label}") }
                            files += file
                        }
                        videos = files.take(plan.video.size); audio = if(plan.audio != null) files.last() else null
                        break
                    } catch (e: IOException) { if(attempt == 2) throw e; update(message = "连接中断，刷新地址后重试"); delay(2000L shl attempt) }
                }
                currentCoroutineContext().ensureActive(); update("Merging", .96f, "合并音视频")
                val output = File(directory, "merged.mp4")
                mux(videos, audio, output)
                currentCoroutineContext().ensureActive()
                val filename = entry.outputFileName ?: (Links.safeName("${entry.item.title} P${entry.item.index} ${entry.item.partTitle}") + " [${entry.item.id.replace(':', '-')}-${id.take(6)}].mp4")
                val uri = publish(output, filename)
                // Publication is a short atomic step from the user's perspective. Keep its URI even if cancellation arrives now.
                BiliApplication.store.update(id) { it.copy(state = "Completed", progress = 1f, message = "下载完成", uri = uri.toString(), finalFileName = runCatching { resolverName(uri) }.getOrNull() ?: filename) }
                BiliApplication.store.get(id)?.let(notify)
                directory.deleteRecursively()
            } catch (e: CancellationException) { update("Paused", message = "已暂停，可继续"); throw e }
            catch (e: Exception) {
                if(!currentCoroutineContext().isActive) { update("Paused", message = "已暂停，可继续"); throw CancellationException("下载已停止", e) }
                update("Failed", message = if(e is BiliException) e.message.orEmpty() else if(e is IOException) "网络或文件写入失败，请检查网络、空间和目录权限。" else "媒体处理失败，请重试或更换画质。")
                BiliApplication.store.update(id) { it.copy(failureKind = (e as? BiliException)?.kind?.name) }
            }
            finally { running.remove(id, job) }
        }
    }
    private class MediaHttpException(val status: Int) : IOException("HTTP $status")
    private suspend fun transfer(part: StreamPart, file: File, progress: (Float) -> Unit) {
        var last: IOException? = null
        for (url in listOf(part.url) + part.backups) {
            try { transferUrl(part.key, url, file, progress); return } catch (e: IOException) { last = e }
        }
        if ((last as? MediaHttpException)?.status == 412)
            throw BiliException(AuthRecovery.message(BiliErrorKind.RequestRestricted, BiliApplication.session.state), BiliErrorKind.RequestRestricted, httpStatus = 412)
        throw last ?: IOException("没有可用媒体源")
    }
    private suspend fun transferUrl(key: String, url: String, file: File, progress: (Float) -> Unit) {
        val secureUrl = url.replaceFirst("http://", "https://")
        if (Uri.parse(secureUrl).scheme != "https") throw BiliException("视频源不是 HTTPS，无法下载。")
        val metaFile = File(file.path + ".json")
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
        var offset = if(file.exists() && meta?.optString("key") == key && (!meta.optString("etag").isNullOrBlank() || !meta.optString("modified").isNullOrBlank())) file.length() else 0
        if(offset > 0 && offset == meta?.optLong("total")) { progress(1f); return }
        val conn = BiliApplication.api.connection(secureUrl)
        val cancelMonitor = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { conn.disconnect() }
        }
        try {
            if(offset > 0) { conn.setRequestProperty("Range", "bytes=$offset-"); conn.setRequestProperty("If-Range", meta!!.optString("etag").ifBlank { meta.optString("modified") }) }
            val code = conn.responseCode
            if(code == 416) { metaFile.delete(); file.delete(); throw IOException("源文件发生变化") }
            if(code !in listOf(200, 206)) throw MediaHttpException(code)
            if(code == 200) offset = 0
            val range = conn.getHeaderField("Content-Range")
            if(code == 206 && range?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() != offset) throw BiliException("断点位置不匹配，请重试。")
            val total = range?.substringAfter('/')?.toLongOrNull() ?: conn.contentLengthLong.takeIf { it >= 0 }?.plus(offset) ?: -1
            val etag = conn.getHeaderField("ETag").orEmpty().takeUnless { it.startsWith("W/") }.orEmpty()
            var count = offset; var last = 0L
            file.outputStreamFor(offset > 0).use { output ->
                metaFile.writeText(JSONObject().put("key", key).put("etag", etag).put("modified", conn.getHeaderField("Last-Modified").orEmpty()).put("total", total).toString())
                conn.inputStream.use { input ->
                    val bytes = ByteArray(65536)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(bytes); if(read < 0) break
                        output.write(bytes, 0, read); count += read
                        val now = System.currentTimeMillis()
                        if(now - last > 700) { last = now; progress(if(total > 0) count.toFloat()/total else 0f) }
                    }
                }
            }
            if(count == 0L || total >= 0 && count != total) throw IOException("文件不完整")
            progress(1f)
        } finally { cancelMonitor.cancel(); conn.disconnect() }
    }
    private fun File.outputStreamFor(append: Boolean) = java.io.FileOutputStream(this, append)

    private suspend fun mux(videos: List<File>, audio: File?, output: File) {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = mutableMapOf<String, Int>()
            fun category(format: MediaFormat): String? = format.getString(MediaFormat.KEY_MIME)?.let { if(it.startsWith("video/")) "video" else if(it.startsWith("audio/")) "audio" else null }
            for (file in listOfNotNull(videos.firstOrNull(), audio)) {
                val ex = MediaExtractor()
                try { ex.setDataSource(file.path); for(i in 0 until ex.trackCount) { val fmt = ex.getTrackFormat(i); val type = category(fmt) ?: continue; if(type !in tracks) tracks[type] = muxer.addTrack(fmt) } }
                finally { ex.release() }
            }
            if("video" !in tracks) throw BiliException("媒体源中没有可导出的视频轨道。")
            muxer.start(); started = true
            val offsets = mutableMapOf<String, Long>(); val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            for(file in videos + listOfNotNull(audio)) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.path)
                    val selected = (0 until ex.trackCount).mapNotNull { i -> category(ex.getTrackFormat(i))?.let { i to it } }.toMap()
                    val durations = selected.map { (track, type) -> val format = ex.getTrackFormat(track); type to if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L }.toMap()
                    val steps = selected.map { (track, type) ->
                        val format = ex.getTrackFormat(track)
                        type to if(type == "video") 1_000_000L / (if(format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1) else 30)
                            else 1_024_000_000L / (if(format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1) else 48000)
                    }.toMap()
                    selected.keys.forEach(ex::selectTrack)
                    val first = mutableMapOf<String, Long>(); val ends = mutableMapOf<String, Long>()
                    while(ex.sampleTrackIndex >= 0) {
                        currentCoroutineContext().ensureActive()
                        val type = selected[ex.sampleTrackIndex] ?: break
                        if(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw BiliException("加密视频轨道无法导出。")
                        buffer.clear(); val size = ex.readSampleData(buffer, 0); if(size < 0) break
                        val base = first.getOrPut(type) { ex.sampleTime }
                        val time = ex.sampleTime - base + offsets.getOrDefault(type, 0)
                        val flags = if(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        val info = MediaCodec.BufferInfo().apply { set(0, size, time, flags) }
                        muxer.writeSampleData(tracks.getValue(type), buffer, info)
                        ends[type] = maxOf(ends.getOrDefault(type, 0), time + steps.getValue(type)); ex.advance()
                    }
                    ends.forEach { (type, end) -> offsets[type] = maxOf(end, offsets.getOrDefault(type, 0) + durations.getOrDefault(type, 0)) }
                } finally { ex.release() }
            }
            muxer.stop(); started = false
        } finally { if(started) runCatching { muxer.stop() }; muxer.release() }
    }
    private val publishLock = Any()
    private fun resolverName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null }
    private fun publish(file: File, requested: String): Uri = synchronized(publishLock) {
        var name = requested
        val resolver = context.contentResolver
        val tree = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("directory", null)
        val occupied = mutableSetOf<String>()
        if(tree != null) DocumentFile.fromTreeUri(context, Uri.parse(tree))?.listFiles()?.mapNotNullTo(occupied) { it.name }
        else resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), "${MediaStore.Video.Media.RELATIVE_PATH} = ?", arrayOf(Environment.DIRECTORY_MOVIES + "/BiliDownload/"), null)?.use { c -> while(c.moveToNext()) occupied += c.getString(0) }
        var suffix = 0
        while(name in occupied) { suffix++; name = FileNaming.candidate(requested, suffix) }
        val uri = if(tree != null) DocumentFile.fromTreeUri(context, Uri.parse(tree))?.createFile("video/mp4", name)?.uri ?: throw IOException("目标目录不可写")
        else resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BiliDownload"); put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: throw IOException("无法创建视频文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw IOException("无法写入目录")
            if(tree == null) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) { if(tree == null) resolver.delete(uri, null, null) else DocumentFile.fromSingleUri(context, uri)?.delete(); throw e }
    }
}
