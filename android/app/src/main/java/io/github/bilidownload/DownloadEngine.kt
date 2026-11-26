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
        if(canceled && BiliApplication.store.get(id)?.state == "Canceled") {
            File(context.cacheDir, "downloads/$id").deleteRecursively()
            if (BiliApplication.store.get(id)?.item?.let { it.youtubeId != null || it.x != null } == true) BiliApplication.youtube.clean(id)
        }
        finishDocuments(id)
    }
    suspend fun run(id: String, notify: (DownloadEntry) -> Unit) {
        try { limit.withPermit { runEntry(id,notify) } }
        finally { withContext(NonCancellable + Dispatchers.IO) { coroutineScope { finishDocuments(id) } } }
    }
    private suspend fun finishDocuments(dependency: String) {
        val rows = BiliApplication.store.entries.value
        for(row in rows) {
            if(row.state == "Queued" && dependency in row.xDestination?.dependencies.orEmpty() && !row.waitsForAttachments(rows))
                limit.withPermit { runEntry(row.id) {} }
        }
    }
    private suspend fun runEntry(id: String, notify: (DownloadEntry) -> Unit) {
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
                val selection = entry.audioOutput
                if (selection?.needsProcessor == true) BiliApplication.audio.requireInstalled()
                if(entry.item.x != null) {
                    val x = entry.item.x
                    val name = requireNotNull(entry.outputFileName)
                    val document = x.document
                    val file = if(document != null) {
                        val files = entry.xDestination?.dependencies.orEmpty().mapNotNull(BiliApplication.store::get)
                            .filter { it.state == "Completed" && it.xDestination?.tree == entry.xDestination?.tree && it.xDestination?.grouped == true }
                            .filter { it.item.x?.handle == x.handle && it.item.x.postId == x.postId }.sortedBy { it.item.index }
                            .mapNotNull { saved -> saved.finalFileName?.let { it to saved.mime } }
                        File(directory,"post.md").apply { writeText(document.markdown(files),Charsets.UTF_8) }
                    } else {
                        if(name.endsWith(".gif")) BiliApplication.audio.requireGif()
                        val (source,_) = BiliApplication.youtube.fetch(entry,directory) { update(progress=it*.95f,message="正在下载 X 附件") }
                        when {
                            selection != null -> File(directory,"audio"+selection.extension).also { exportAudio(listOf(source),it,selection,entry.item) }
                            name.endsWith(".gif") -> File(directory,"animation.gif").also { update("Merging", .96f,"正在转换 GIF"); BiliApplication.audio.convertGif(source,it) }
                            else -> source
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val uri = publish(file,name,entry.mime,entry.xDestination,x)
                    BiliApplication.store.update(id) { it.copy(state="Completed", progress=1f, message="下载完成", uri=uri.toString(), finalFileName=runCatching { resolverName(uri) }.getOrNull() ?: name) }
                    BiliApplication.store.get(id)?.let(notify)
                    directory.deleteRecursively()
                    if(document == null) BiliApplication.youtube.clean(id)
                    return@withLock
                }
                var videos = emptyList<File>(); var audio: File? = null
                if (entry.item.youtubeId != null) {
                    val tracks = BiliApplication.youtube.fetch(entry, directory) { fraction -> update(progress = fraction * .95f, message = if(selection != null) "正在下载音轨" else "正在下载 YouTube 视频") }
                    videos = listOf(tracks.first); audio = tracks.second
                } else for (attempt in 0..2) {
                    try {
                        val plan = if(selection != null) BiliApplication.api.audioPlan(entry.item, selection) else BiliApplication.api.plan(entry.item, entry.quality)
                        val parts = plan.video + listOfNotNull(plan.audio)
                        val files = mutableListOf<File>()
                        for ((index, part) in parts.withIndex()) {
                            val file = File(directory, Links.safeName(part.key) + ".part")
                            transfer(part, file) { fraction -> update(progress = (index + fraction) / parts.size * .95f, message = "正在下载  ${plan.variant.label}") }
                            files += file
                        }
                        videos = files.take(plan.video.size); audio = if(plan.audio != null) files.last() else null
                        break
                    } catch (e: IOException) { if(attempt == 2) throw e; update(message = "连接中断，刷新地址后重试"); delay(2000L shl attempt) }
                }
                currentCoroutineContext().ensureActive(); update("Merging", .96f, if(selection == null) "合并音视频" else "处理音频")
                val output = File(directory, "merged" + (selection?.extension ?: ".mp4"))
                if(selection == null) mux(videos, audio, output)
                else exportAudio(videos, output, selection, entry.item)
                currentCoroutineContext().ensureActive()
                val filename = entry.outputFileName ?: (Links.safeName("${entry.item.title} P${entry.item.index} ${entry.item.partTitle}") + " [${entry.item.id.replace(':', '-')}-${id.take(6)}].mp4")
                val uri = publish(output, filename, selection?.mime)
                // Publication is a short atomic step from the user's perspective. Keep its URI even if cancellation arrives now.
                BiliApplication.store.update(id) { it.copy(state = "Completed", progress = 1f, message = "下载完成", uri = uri.toString(), finalFileName = runCatching { resolverName(uri) }.getOrNull() ?: filename) }
                BiliApplication.store.get(id)?.let(notify)
                directory.deleteRecursively()
                if (entry.item.youtubeId != null) BiliApplication.youtube.clean(id)
            } catch (e: CancellationException) { update("Paused", message = "已暂停，可继续"); throw e }
            catch (e: Exception) {
                if(!currentCoroutineContext().isActive) { update("Paused", message = "已暂停，可继续"); throw CancellationException("下载已停止", e) }
                update("Failed", message = if(e is BiliException) e.message.orEmpty() else if(e is IOException) "网络或文件写入失败，请检查网络、空间和目录权限。" else "媒体处理失败，请重试或更换画质。")
                BiliApplication.store.update(id) { it.copy(failureKind = (e as? BiliException)?.kind?.name) }
            }
            finally { running.remove(id, job) }
        }
    }
    internal suspend fun exportAudio(videos: List<File>, output: File, selection: AudioOutput, item: MediaItem) {
        var finished = false
        if(selection.mp3Kbps == 0) {
            val head = ByteArray(12)
            videos.first().inputStream().use { it.read(head) }
            val magic = String(head, Charsets.ISO_8859_1)
            if(!selection.track.mixed && videos.size == 1 &&
                (selection.extension == ".flac" && magic.startsWith("fLaC") || selection.extension == ".opus" && magic.startsWith("OggS"))) {
                videos.first().copyTo(output, overwrite = true); finished = true
            } else if(selection.extension in listOf(".opus",".m4a")) {
                try { mux(videos,null,output,selection.extension); finished = true }
                catch(e: CancellationException) { throw e } catch(_: Exception) { output.delete() }
            }
        }
        if(!finished) BiliApplication.audio.convert(videos,output,selection)
        AudioTags.write(output,selection.track.tags ?: MusicTags(if(item.partCount > 1) item.partTitle else item.title,cover = item.cover))
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

    private suspend fun mux(videos: List<File>, audio: File?, output: File, audioExtension: String? = null) {
        val muxer = MediaMuxer(output.path, if(audioExtension == ".opus") MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = mutableMapOf<String, Int>()
            fun category(format: MediaFormat): String? = format.getString(MediaFormat.KEY_MIME)?.let { if(audioExtension == null && it.startsWith("video/")) "video" else if(it.startsWith("audio/")) "audio" else null }
            for (file in listOfNotNull(videos.firstOrNull(), audio)) {
                val ex = MediaExtractor()
                try { ex.setDataSource(file.path); for(i in 0 until ex.trackCount) { val fmt = ex.getTrackFormat(i); val type = category(fmt) ?: continue; if(type !in tracks) tracks[type] = muxer.addTrack(fmt) } }
                finally { ex.release() }
            }
            if((if(audioExtension == null) "video" else "audio") !in tracks) throw BiliException("媒体源中没有可导出的视频轨道。")
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
    internal fun publish(file: File, requested: String, audioMime: String? = null, destination: XDestination? = null, x: XContent? = null): Uri = synchronized(publishLock) {
        val mime = audioMime ?: "video/mp4"
        val folder = when {
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val postFolder = if(destination?.grouped == true && x != null) Links.safeName(x.handle+"-"+x.postId) else null
        val collection = when {
            postFolder != null || folder == Environment.DIRECTORY_DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            folder == Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            folder == Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val directory = if(postFolder == null) "$folder/BiliDownload" else Environment.DIRECTORY_DOWNLOADS+"/BiliDownload/"+postFolder
        var name = requested
        val resolver = context.contentResolver
        val tree = if(destination != null) destination.tree else context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("directory", null)
        val parent = tree?.let {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(it)) ?: throw IOException("目录不可用")
            if(destination == null) root else {
                val child = postFolder ?: folder
                (root.findFile(child) ?: root.createDirectory(child))?.takeIf { it.isDirectory } ?: throw IOException("无法创建子目录")
            }
        }
        val occupied = mutableSetOf<String>()
        if(parent != null) parent.listFiles().mapNotNullTo(occupied) { it.name }
        else resolver.query(collection, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), "${MediaStore.Video.Media.RELATIVE_PATH} = ?", arrayOf("$directory/"), null)?.use { c -> while(c.moveToNext()) occupied += c.getString(0) }
        var suffix = 0
        while(name in occupied) { suffix++; name = FileNaming.candidate(requested, suffix) }
        val uri = if(parent != null) parent.createFile(mime, name)?.uri ?: throw IOException("目标目录不可写")
        else resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, directory); put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: throw IOException("无法创建视频文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw IOException("无法写入目录")
            if(tree == null) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) { if(tree == null) resolver.delete(uri, null, null) else DocumentFile.fromSingleUri(context, uri)?.delete(); throw e }
    }
}
