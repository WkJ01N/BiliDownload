package io.github.bilidownload

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

data class PlaylistPage(val id: String, val start: Int, val music: Boolean = false)
data class YoutubeCatalog(val items: List<MediaItem>, val target: String? = null, val more: PlaylistPage? = null, val variants: List<Variant>? = null, val audio: List<AudioTrack>? = null, val defaults: List<String> = target?.let(::listOf).orEmpty())

class YoutubeClient(private val context: Context, val session: Session) {
    private val engine = LocalMediaEngine(context.applicationContext)
    val xSession = Session(context,"x-session","BiliDownloadXSession")

    suspend fun import(uri: Uri, x: Boolean = false) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                if (out.size() + n > YoutubeCookies.MAX_BYTES) throw BiliException("会话文件超过 128 KiB，请仅导出对应网站的记录。")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw BiliException("无法读取会话文件。")
        (if(x) xSession else session).save(YoutubeCookies.filter(bytes.toString(Charsets.UTF_8), x=x))
    }
    private fun error(code: String): BiliException = when (code) {
        "SessionRequired" -> BiliException("该视频需要 YouTube 账号，请导入会话后重试。", BiliErrorKind.YoutubeSession)
        "VerifyAccount" -> BiliException("YouTube 要求验证账号。请在 YouTube 登录并确认可以播放，再到设置中导入或更新会话。", BiliErrorKind.YoutubeSession)
        "SessionExpired" -> BiliException("YouTube 会话已失效或账号无权访问，请检查账号或替换会话。", BiliErrorKind.YoutubeSession)
        "Membership" -> BiliException("该账号没有此视频所需的频道会员资格。", BiliErrorKind.YoutubeSession)
        "Restricted" -> BiliException("YouTube 暂时限制了请求，请稍后重试。")
        "Protected" -> BiliException("此视频受 DRM 保护，无法保存为 MP4。")
        "Live" -> BiliException("回放尚未发布，请稍后重新解析。")
        "ReplayProcessing" -> BiliException("直播已结束，YouTube 正在处理回放，请稍后重新解析。")
        "TrackChanged" -> BiliException("所选音轨已不可用，请重新解析并选择音质。", BiliErrorKind.TrackChanged)
        "NoAudio" -> BiliException("当前没有可下载的音轨。")
        "NoFormat" -> BiliException("没有符合画质上限的 H.264 和 AAC 格式。")
        "Engine" -> BiliException("下载组件无法运行，请在设置中检查组件。", BiliErrorKind.YoutubeComponent)
        "Network" -> BiliException("无法连接 YouTube 媒体服务，请检查网络后重试。")
        else -> BiliException("YouTube 视频或播放列表不可用。")
    }
    private suspend fun execute(request: JSONObject, directory: File? = null, progress: (Float) -> Unit = {}): JSONObject = withContext(Dispatchers.IO) {
        val x = request.optString("platform") == "x"
        val cookies = if (x) xSession.get() else session.get()
        if (cookies.isNotEmpty()) request.put("cookies", cookies)
        engine.run(request, directory, progress) { code -> if (x) xError(code) else error(code) }
    }
    private fun xError(code: String) = when(code) {
        "SessionRequired","SessionExpired","VerifyAccount" -> BiliException("X 需要验证账号，请导入或更新自己的 X 会话后重试。", BiliErrorKind.XSession)
        "Forbidden","Protected" -> BiliException("当前账号无法访问此帖子。")
        "Restricted" -> BiliException("X 暂时限制了请求，请稍后重试。")
        "Deleted" -> BiliException("帖子已删除。")
        "TrackChanged" -> BiliException("帖子附件已更改，请重新解析。")
        "Network" -> BiliException("无法连接 X，请检查网络后重试。")
        "NoFormat" -> BiliException("此附件没有可下载的格式。")
        "Engine" -> BiliException("下载组件无法运行，请在设置中检查组件。", BiliErrorKind.XComponent)
        else -> BiliException("X 帖子或附件不可用。")
    }
    suspend fun xPost(link: XLink): YoutubeCatalog {
        val info = metadata(JSONObject().put("platform","x").put("op","inspect").put("id",link.id))
        return XContent.catalog(info,link)
    }
    private suspend fun metadata(request: JSONObject): JSONObject =
        withTimeoutOrNull(120_000) { execute(request) } ?: throw BiliException("请求超时，请稍后重试。")
    private fun item(json: JSONObject, playlist: String? = null, music: Boolean = false): MediaItem {
        val id = json.getString("id"); val title = json.getString("title")
        return MediaItem(if(music) "ytm:$id" else "yt:$id", title, title, "video", 0, "", 0, 0, json.getInt("index"), json.getString("cover"), 1, id, playlist, music)
    }
    private suspend fun inspect(id: String, music: Boolean = false, audio: Boolean = music) = metadata(JSONObject().put("op", "inspect").put("id", id).put("music", music).put("mode", if(audio) "audio" else "video"))
    suspend fun audio(item: MediaItem): List<AudioTrack> = if(item.x != null) {
        val source = item.x
        val v = source.variants.maxByOrNull { it.quality }
        if(source.audio && v != null) listOf(AudioTrack(requireNotNull(v.formatId),"AAC",0,"",0,source.duration,true,v.estimatedBytes,null,MusicTags(item.title,source.handle),container="mp4")) else emptyList()
    } else if(item.youtubeId == null) BiliApplication.api.audio(item).map { it.first } else AudioMedia.youtube(inspect(item.youtubeId, item.music, true))
    private fun choices(info: JSONObject) = info.getJSONArray("variants").objects().map {
        Variant(it.getInt("height"), "${it.getInt("height")}p", it.optLong("bytes").takeIf { bytes -> bytes > 0 }, it.getString("format"))
    }
    suspend fun variants(item: MediaItem) = item.x?.variants ?: if (item.youtubeId == null) BiliApplication.api.variants(item) else choices(inspect(item.youtubeId))
    suspend fun page(cursor: PlaylistPage): YoutubeCatalog {
        val info = metadata(JSONObject().put("op", "playlist").put("id", cursor.id).put("start", cursor.start).put("music", cursor.music))
        return YoutubeCatalog(info.getJSONArray("items").objects().map { item(it, cursor.id, cursor.music) },
            more = info.getInt("next").takeIf { it > cursor.start }?.let { PlaylistPage(cursor.id, it, cursor.music) })
    }
    suspend fun resolve(link: YoutubeLink, expand: Boolean): YoutubeCatalog {
        if (link.playlist != null && (link.video == null || expand)) {
            var page = page(PlaylistPage(link.playlist, 1, link.music))
            val items = page.items.toMutableList()
            while (page.more != null && (items.isEmpty() || link.video != null && items.none { it.youtubeId == link.video })) {
                page = page(page.more!!); items.addAll(page.items)
            }
            if (link.video != null && items.none { it.youtubeId == link.video }) items += item(inspect(link.video, link.music), music = link.music)
            return YoutubeCatalog(items, link.video?.let { if(link.music) "ytm:$it" else "yt:$it" }, page.more)
        }
        val info = inspect(requireNotNull(link.video), link.music); val item = item(info, music = link.music)
        return YoutubeCatalog(listOf(item), item.id, variants = if(link.music) null else choices(info), audio = if(link.music) AudioMedia.youtube(info) else null)
    }
    suspend fun fetch(entry: DownloadEntry, dir: File, progress: (Float) -> Unit): Pair<File, File?> = withContext(Dispatchers.IO) {
        require(dir.canonicalFile == engine.taskDirectory(entry.id).canonicalFile)
        val x = entry.item.x
        val request = if(x != null) JSONObject().put("platform","x").put("op","fetch").put("id",x.postId)
            .put("attachment",x.attachment).put("height",if(entry.audioOutput != null) x.variants.maxOfOrNull { it.quality } ?: throw xError("NoFormat") else entry.quality)
            .put("format",entry.audioOutput?.track?.id ?: entry.formatId) else JSONObject().put("op", "fetch").put("id", entry.item.youtubeId)
            .put("height", entry.quality).put("format", entry.audioOutput?.track?.id ?: entry.formatId)
            .put("mode", if(entry.audioOutput != null) "audio" else "video").put("music", entry.item.music)
            .put("mixed", entry.audioOutput?.track?.mixed ?: false)
        val result = execute(request, dir, progress)
        fun track(name: String): File = File(dir, name).takeIf { it.isFile && it.length() > 0 }
            ?: throw BiliException("下载文件不完整，请重试。")
        if (x != null) {
            if (result.optString("extension") != x.extension) throw xError("TrackChanged")
            val file = track("x.track")
            if (file.length() != result.getLong("bytes")) throw BiliException("下载文件不完整，请重试。")
            return@withContext file to null
        }
        val tracks = if (entry.audioOutput != null) track("audio.track") to null
            else track("video.track") to if (result.getBoolean("audio")) track("audio.track") else null
        BiliApplication.store.update(entry.id) { it.copy(formatId = result.getString("format")) }
        tracks
    }
    suspend fun clean(id: String) = withContext(Dispatchers.IO) {
        engine.taskDirectory(id).deleteRecursively()
        Unit
    }
}
