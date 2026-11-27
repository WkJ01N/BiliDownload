package io.github.bilidownload

import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

class DownloadEngine(private val context: Context) {
    private val publisher = MediaPublisher(context)
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
                val filename = entry.outputFileName ?: (Links.safeName("${entry.item.title} P${entry.item.index} ${entry.item.partTitle}") + " [${entry.item.id.replace(':', '-')}-${id.take(6)}].mp4")
                currentCoroutineContext().ensureActive()
                val uri = if (selection == null) {
                    finishVideo(videos, audio, directory, filename) { message -> update("Merging", .96f, message) }
                } else {
                    update("Merging", .96f, "处理音频")
                    val output = File(directory, "merged" + selection.extension)
                    exportAudio(videos, output, selection, entry.item)
                    update("Merging", .99f, "保存音频")
                    publish(output, filename, selection.mime)
                }
                // Publication is a short atomic step from the user's perspective. Keep its URI even if cancellation arrives now.
                BiliApplication.store.update(id) { it.copy(state = "Completed", progress = 1f, message = "下载完成", uri = uri.toString(), finalFileName = runCatching { resolverName(uri) }.getOrNull() ?: filename) }
                BiliApplication.store.get(id)?.let(notify)
                directory.deleteRecursively()
                if (entry.item.youtubeId != null) BiliApplication.youtube.clean(id)
            } catch (e: CancellationException) {
                if (BiliApplication.store.get(id)?.state != "Completed") update("Paused", message = "已暂停，可继续")
                throw e
            }
            catch (e: Exception) {
                if (BiliApplication.store.get(id)?.state == "Completed") return@withLock
                if(!currentCoroutineContext().isActive) { update("Paused", message = "已暂停，可继续"); throw CancellationException("下载已停止", e) }
                update("Failed", message = if(e is BiliException) e.message.orEmpty() else if(e is IOException) "网络或文件写入失败，请检查网络、空间和目录权限。" else "媒体处理失败，请重试或更换画质。")
                BiliApplication.store.update(id) { it.copy(failureKind = (e as? BiliException)?.kind?.name) }
            }
            finally { running.remove(id, job) }
        }
    }
    internal suspend fun exportAudio(videos: List<File>, output: File, selection: AudioOutput, item: MediaItem) {
        var finished = false
        if (selection.mp3Kbps == 0) {
            val source = videos.singleOrNull()?.takeUnless { selection.track.mixed }
            val unchanged = source != null && when (selection.extension) {
                ".flac", ".opus" -> {
                    val head = ByteArray(4)
                    source.inputStream().use { it.read(head) }
                    String(head, Charsets.ISO_8859_1) == if (selection.extension == ".flac") "fLaC" else "OggS"
                }
                ".m4a" -> canSaveMp4Directly(source, audioOnly = true)
                else -> false
            }
            if (unchanged) {
                output.outputStream().use { copyMediaFile(source!!, it) }
                finished = true
            } else if (selection.extension == ".opus" || selection.extension == ".m4a") {
                try {
                    val format = if (selection.extension == ".opus") MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                        else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    muxMedia(videos, null, MediaMuxer(output.path, format), audioOnly = true)
                    finished = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    output.delete()
                }
            }
        }
        if(!finished) BiliApplication.audio.convert(videos,output,selection)
        AudioTags.write(output,selection.track.tags ?: MusicTags(if(item.partCount > 1) item.partTitle else item.title,cover = item.cover))
    }

    internal suspend fun finishVideo(videos: List<File>, audio: File?, work: File, filename: String,
                                     status: (String) -> Unit = {}): Uri {
        val tree = publisher.selectedTree()
        val direct = videos.singleOrNull()?.takeIf { audio == null && canSaveMp4Directly(it) }
        if (direct != null) {
            status("保存视频")
            return publisher.publish(direct, filename, "video/mp4", null, null, tree)
        }
        status("合并音视频")
        val staging = File(work, "merged.mp4")
        val video = videos.singleOrNull()
        val streamCopied = if (video != null && audio != null) {
            try {
                BiliApplication.audio.muxVideo(video, audio, staging)
                true
            } catch (_: BiliException) { false }
        } else false
        if (!streamCopied) muxMedia(videos, audio, MediaMuxer(staging.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
        status("保存视频")
        return publisher.publish(staging, filename, "video/mp4", null, null, tree)
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

    private fun resolverName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    internal suspend fun publish(file: File, requested: String, audioMime: String? = null,
                                 destination: XDestination? = null, x: XContent? = null): Uri =
        publisher.publish(file, requested, audioMime ?: "video/mp4", destination, x)
}
