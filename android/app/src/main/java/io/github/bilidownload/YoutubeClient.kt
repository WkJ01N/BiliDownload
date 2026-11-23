package io.github.bilidownload

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.bilidownload.bridge.IYoutubeEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaylistPage(val id: String, val start: Int)
data class YoutubeCatalog(val items: List<MediaItem>, val target: String? = null, val more: PlaylistPage? = null, val variants: List<Variant>? = null)

class YoutubeClient(private val context: Context, val session: Session) {
    companion object {
        const val PACKAGE = "io.github.bilidownload.youtube"
        const val APK = "https://github.com/WkJ01N/BiliDownload/releases/download/v0.2.0/BiliDownload-0.2.0-youtube.apk"
    }
    fun installation(): String = try {
        val info = context.packageManager.getPackageInfo(PACKAGE, 0)
        when {
            context.packageManager.checkSignatures(PACKAGE, context.packageName) != PackageManager.SIGNATURE_MATCH -> "扩展签名不匹配"
            info.longVersionCode < 7 -> "扩展需要更新"
            else -> "已安装"
        }
    } catch (_: PackageManager.NameNotFoundException) { "未安装" }
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                if (out.size() + n > YoutubeCookies.MAX_BYTES) throw BiliException("会话文件超过 128 KiB，请仅导出 YouTube 记录。")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw BiliException("无法读取会话文件。")
        session.save(YoutubeCookies.filter(bytes.toString(Charsets.UTF_8)))
    }
    @Volatile private var opened: IYoutubeEngine? = null
    fun acceptActivation(engine: IBinder?) { opened = engine?.let { IYoutubeEngine.Stub.asInterface(it) } }
    private fun component(message: String) = BiliException(message, BiliErrorKind.YoutubeComponent)

    private suspend fun <T> withEngine(block: suspend (IYoutubeEngine) -> T): T = withContext(Dispatchers.IO) {
        when (val status = installation()) {
            "已安装" -> Unit
            "未安装" -> throw component("请安装 YouTube 扩展。")
            else -> throw component(status)
        }
        val connected = CompletableDeferred<IYoutubeEngine>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(IYoutubeEngine.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { connected.completeExceptionally(component("YouTube 扩展已断开，请重试。")) }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        val intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.EngineService")).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            val remote = opened
            if (remote != null && remote.asBinder().isBinderAlive && remote.version() == 1) return@withContext block(remote)
            throw BiliException("请打开 YouTube 扩展以连接下载服务。", BiliErrorKind.YoutubeConnect)
        }
        try {
            val remote = withTimeoutOrNull(8000) { connected.await() } ?: throw component("连接 YouTube 扩展超时。")
            if (remote.version() != 1) throw component("请更新 YouTube 扩展。")
            block(remote)
        } finally { context.unbindService(connection) }
    }
    private fun error(code: String): BiliException = when (code) {
        "SessionRequired" -> BiliException("该视频需要 YouTube 账号，请导入会话后重试。", BiliErrorKind.YoutubeSession)
        "SessionExpired" -> BiliException("YouTube 会话已失效或账号无权访问，请检查账号或替换会话。", BiliErrorKind.YoutubeSession)
        "Membership" -> BiliException("该账号没有此视频所需的频道会员资格。", BiliErrorKind.YoutubeSession)
        "Restricted" -> BiliException("YouTube 暂时限制了请求，请稍后重试。")
        "Protected" -> BiliException("此视频受 DRM 保护，无法保存为 MP4。")
        "Live" -> BiliException("直播尚未结束，请在回放发布后重试。")
        "NoFormat" -> BiliException("没有符合画质上限的 H.264 和 AAC 格式。")
        "Engine" -> component("YouTube 扩展无法运行，请检查扩展版本。")
        "Network" -> BiliException("无法连接 YouTube 媒体服务，请检查网络后重试。")
        else -> BiliException("YouTube 视频或播放列表不可用。")
    }
    private suspend fun execute(remote: IYoutubeEngine, json: JSONObject, task: String? = null, progress: (Float) -> Unit = {}): JSONObject = coroutineScope {
        val id = UUID.randomUUID().toString()
        val cookies = session.get()
        if (cookies.isNotEmpty()) json.put("cookies", cookies)
        val owner = Binder()
        val pipe = remote.run(id, task, json.toString(), owner)
        val cancel = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { remote.cancel(id) } }
        }
        try {
            var result: JSONObject? = null
            ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    ensureActive()
                    val data = JSONObject(line)
                    if (data.has("error")) throw error(data.getString("error"))
                    if (data.has("progress")) progress(data.getDouble("progress").toFloat().coerceIn(0f, 1f))
                    data.optJSONObject("result")?.let { result = it }
                }
            }
            ensureActive()
            result ?: throw component("YouTube 扩展已中断，请重试。")
        } finally { cancel.cancel() }
    }
    private suspend fun metadata(request: JSONObject): JSONObject = withEngine { remote ->
        withTimeoutOrNull(120_000) { execute(remote, request) } ?: throw BiliException("YouTube 响应超时，请重试。")
    }
    private fun item(json: JSONObject, playlist: String? = null): MediaItem {
        val id = json.getString("id"); val title = json.getString("title")
        return MediaItem("yt:$id", title, title, "video", 0, "", 0, 0, json.getInt("index"), json.getString("cover"), 1, id, playlist)
    }
    private suspend fun inspect(id: String) = metadata(JSONObject().put("op", "inspect").put("id", id))
    private fun choices(info: JSONObject) = info.getJSONArray("variants").objects().map {
        Variant(it.getInt("height"), "${it.getInt("height")}p", it.optLong("bytes").takeIf { bytes -> bytes > 0 }, it.getString("format"))
    }
    suspend fun variants(item: MediaItem) = if (item.youtubeId == null) BiliApplication.api.variants(item) else choices(inspect(item.youtubeId))
    suspend fun page(cursor: PlaylistPage): YoutubeCatalog {
        val info = metadata(JSONObject().put("op", "playlist").put("id", cursor.id).put("start", cursor.start))
        return YoutubeCatalog(info.getJSONArray("items").objects().map { item(it, cursor.id) },
            more = info.getInt("next").takeIf { it > cursor.start }?.let { PlaylistPage(cursor.id, it) })
    }
    suspend fun resolve(link: YoutubeLink, expand: Boolean): YoutubeCatalog {
        if (link.playlist != null && (link.video == null || expand)) {
            var page = page(PlaylistPage(link.playlist, 1))
            val items = page.items.toMutableList()
            while (page.more != null && (items.isEmpty() || link.video != null && items.none { it.youtubeId == link.video })) {
                page = page(page.more!!); items.addAll(page.items)
            }
            if (link.video != null && items.none { it.youtubeId == link.video }) items += item(inspect(link.video))
            return YoutubeCatalog(items, link.video?.let { "yt:$it" }, page.more)
        }
        val info = inspect(requireNotNull(link.video)); val item = item(info)
        return YoutubeCatalog(listOf(item), item.id, variants = choices(info))
    }
    suspend fun fetch(entry: DownloadEntry, dir: File, progress: (Float) -> Unit): Pair<File, File?> = withEngine { remote ->
        val result = execute(remote, JSONObject().put("op", "fetch").put("id", entry.item.youtubeId)
            .put("height", entry.quality).put("format", entry.formatId), entry.id, progress)
        suspend fun copy(audio: Boolean): File {
            val file = File(dir, if (audio) "audio.track" else "video.track")
            val job = currentCoroutineContext()
            ParcelFileDescriptor.AutoCloseInputStream(remote.openTrack(entry.id, audio)).use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) { job.ensureActive(); val count = input.read(buf); if (count < 0) break; out.write(buf, 0, count) }
                }
            }
            return file
        }
        val tracks = copy(false) to if (result.getBoolean("audio")) copy(true) else null
        BiliApplication.store.update(entry.id) { it.copy(formatId = result.getString("format")) }
        tracks
    }
    suspend fun clean(id: String) {
        try { withEngine { it.clean(id) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* Extension was removed or is offline. */ }
    }
}
