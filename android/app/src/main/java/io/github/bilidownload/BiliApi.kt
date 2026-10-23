package io.github.bilidownload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

class BiliApi(private val session: Session) {
    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private val order = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A")
        fun sign(values: Map<String, String>, img: String, sub: String, time: Long): String {
            val key = img + sub; require(key.length == 64)
            val mixed = order.take(32).map { key[it] }.joinToString("")
            val query = (values + ("wts" to time.toString())).toSortedMap().entries.joinToString("&") { (k,v) ->
                "${encode(k)}=${encode(v.filterNot { it in "!'()*" })}" }
            val hash = MessageDigest.getInstance("MD5").digest((query + mixed).toByteArray()).joinToString("") { "%02x".format(it) }
            return "$query&w_rid=$hash"
        }
        fun label(q: Int) = when(q) { 16 -> "360p"; 32 -> "480p"; 64 -> "720p"; 74 -> "720p 60帧"; 80 -> "1080p"; 112 -> "1080p 高码率"; 116 -> "1080p 60帧"; 120 -> "4K"; 127 -> "8K"; else -> "画质 $q" }
    }
    fun connection(url: String, cookieOverride: String? = null): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = false
        setRequestProperty("User-Agent", UA); setRequestProperty("Referer", "https://www.bilibili.com/")
        val host = URI(url).host.orEmpty().lowercase()
        if (host == "bilibili.com" || host.endsWith(".bilibili.com")) (cookieOverride ?: session.get()).takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
    }
    suspend fun api(url: String, cookieOverride: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val conn = connection(url, cookieOverride)
        val cancelMonitor = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { conn.disconnect() }
        }
        try {
            if (conn.responseCode != 200) {
                val status = conn.responseCode
                val kind = when(status) { 412 -> BiliErrorKind.RequestRestricted; 401 -> BiliErrorKind.LoginRequired; else -> BiliErrorKind.Unknown }
                if(kind == BiliErrorKind.LoginRequired && cookieOverride == null) session.state = SessionState.Expired
                throw BiliException(if(kind == BiliErrorKind.Unknown) "B 站请求失败（HTTP $status），请稍后重试。" else AuthRecovery.message(kind, session.state), kind, httpStatus = status)
            }
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = root.optInt("code")
            if(cookieOverride == null && url.startsWith("https://api.bilibili.com/x/web-interface/nav") && root.optJSONObject("data") != null) {
                session.state = if(root.getJSONObject("data").optBoolean("isLogin")) SessionState.Authenticated else if(session.state == SessionState.Guest) SessionState.Guest else SessionState.Expired
            }
            if (code != 0 && !(url.contains("/x/web-interface/nav") && code == -101 && root.optJSONObject("data")?.has("wbi_img") == true))
            {
                val kind = when(code) { -101 -> BiliErrorKind.LoginRequired; -412, -352, -509 -> BiliErrorKind.RequestRestricted; else -> BiliErrorKind.Unknown }
                if(kind == BiliErrorKind.LoginRequired && cookieOverride == null) session.state = SessionState.Expired
                throw BiliException(if(kind == BiliErrorKind.Unknown) "无法获取内容：${root.optString("message", code.toString())}" else AuthRecovery.message(kind, session.state), kind, apiCode = code)
            }
            root.optJSONObject("data") ?: root.optJSONObject("result") ?: throw BiliException("B 站接口响应格式发生变化。")
        } finally { cancelMonitor.cancel(); conn.disconnect() }
    }
    suspend fun resolve(input: String): List<MediaItem> {
        val links = Links.extract(input); if (links.isEmpty()) throw BiliException("没有找到 B 站链接或 BV 号。")
        val items = mutableListOf<MediaItem>()
        for (original in links) {
            var url = original.replaceFirst("http://", "https://")
            repeat(6) {
                if (!Links.allowed(url)) throw BiliException("短链接跳转到不支持的网站。")
                if (URI(url).host == "b23.tv") url = withContext(Dispatchers.IO) {
                    val conn = connection(url)
                    try { if (conn.responseCode !in 300..399) throw BiliException("短链接无法跳转。")
                        URI(url).resolve(conn.getHeaderField("Location") ?: throw BiliException("短链接缺少跳转地址。")).toString()
                    } finally { conn.disconnect() }
                }
            }
            if (!Links.allowed(url)) throw BiliException("不支持的跳转地址。")
            val path = URI(url).path
            val video = Regex("/video/(BV[a-zA-Z0-9]{10}|av[0-9]+)").find(path)
            if (video != null) {
                val id = video.groupValues[1]
                val data = api("https://api.bilibili.com/x/web-interface/view?" + if (id.startsWith("av")) "aid=${id.drop(2)}" else "bvid=$id")
                data.getJSONArray("pages").objects().forEachIndexed { index, p -> items += MediaItem("video:${p.getLong("cid")}", data.getString("title"), p.optString("part"), "video", data.getLong("aid"), data.getString("bvid"), p.getLong("cid"), 0, index + 1, data.optString("pic")) }
            } else if (path.contains("/bangumi/") || path.contains("/cheese/")) {
                val match = Regex("/(ep|ss|md)([0-9]+)").find(path) ?: throw BiliException("请提供分集或系列播放链接。")
                var prefix = match.groupValues[1]; var id = match.groupValues[2]
                val kind = if (path.contains("/cheese/")) "course" else "bangumi"
                if (prefix == "md") { id = api("https://api.bilibili.com/pgc/review/user?media_id=$id").getJSONObject("media").getLong("season_id").toString(); prefix = "ss" }
                val endpoint = if (kind == "course") "pugv/view/web/season" else "pgc/view/web/season"
                val data = api("https://api.bilibili.com/$endpoint?${if(prefix == "ep") "ep_id" else "season_id"}=$id")
                val episodes = data.optJSONArray("episodes").objects().toMutableList()
                data.optJSONArray("section").objects().forEach { episodes += it.optJSONArray("episodes").objects() }
                episodes.distinctBy { it.optLong("id") }.forEachIndexed { index, e -> items += MediaItem("$kind:${e.getLong("id")}", data.getString("title"), "${e.optString("title")} ${e.optString("long_title")}".trim(), kind, e.optLong("aid"), e.optString("bvid"), e.optLong("cid"), e.getLong("id"), index + 1, e.optString("cover", data.optString("cover"))) }
            } else throw BiliException("请提供视频、番剧、课程或已发布回放的视频链接；首版不录制直播。")
        }
        return items.distinctBy { it.id }.ifEmpty { throw BiliException("没有找到可下载条目。") }
    }
    private suspend fun info(item: MediaItem, quality: Int): JSONObject {
        val values = mutableMapOf("cid" to item.cid.toString(), "qn" to quality.toString(), "fnval" to "4048", "fnver" to "0", "fourk" to "1")
        val url: String
        if (item.kind == "video") {
            values["bvid"] = item.bvid
            val wbi = api("https://api.bilibili.com/x/web-interface/nav").getJSONObject("wbi_img")
            fun key(name: String) = URI(wbi.getString(name)).path.substringAfterLast('/').substringBefore('.')
            url = "https://api.bilibili.com/x/player/wbi/playurl?" + sign(values, key("img_url"), key("sub_url"), System.currentTimeMillis() / 1000)
        } else {
            values["ep_id"] = item.episodeId.toString(); if(item.kind == "bangumi") values["avid"] = item.aid.toString()
            url = "https://api.bilibili.com/${if(item.kind == "course") "pugv/player/web/playurl" else "pgc/player/web/playurl"}?" + values.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        }
        val data = api(url)
        if (data.optInt("is_drm") != 0 || data.optInt("drm_tech_type") != 0) throw BiliException("视频源受保护，无法导出为普通文件。")
        if (data.optBoolean("is_preview") || data.optInt("is_preview") != 0) throw BiliException("当前只能获取试看内容，请登录有完整观看权限的账号。")
        return data
    }
    private fun variants(data: JSONObject): List<Variant> {
        val dash = data.optJSONObject("dash")
        if (dash != null) return dash.optJSONArray("video").objects().filter { it.optInt("codecid") == 7 || it.optString("codecs").startsWith("avc") }
            .map { Variant(it.getInt("id"), label(it.getInt("id"))) }.distinctBy { it.quality }.sortedBy { it.quality }
        if (data.optJSONArray("durl")?.length() ?: 0 > 0) return listOf(Variant(data.optInt("quality"), label(data.optInt("quality"))))
        throw BiliException("没有可下载的兼容视频源，请检查账号权限。")
    }
    suspend fun variants(item: MediaItem) = variants(info(item, 127))
    suspend fun plan(item: MediaItem, quality: Int): DownloadPlan {
        val data = info(item, quality)
        val selected = variants(data).filter { it.quality <= quality }.maxByOrNull { it.quality } ?: throw BiliException("没有符合画质上限的 H.264 视频。")
        fun part(v: JSONObject, key: String) = StreamPart(key, v.optString("baseUrl", v.optString("base_url")), (v.optJSONArray("backupUrl") ?: v.optJSONArray("backup_url")).strings())
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val video = dash.getJSONArray("video").objects().first { it.getInt("id") == selected.quality && (it.optInt("codecid") == 7 || it.optString("codecs").startsWith("avc")) }
            val audios = dash.optJSONArray("audio").objects()
            val audio = audios.filter { it.optString("codecs").startsWith("mp4a") }.maxByOrNull { it.optLong("bandwidth") }
            if (audios.isNotEmpty() && audio == null) throw BiliException("没有可用的 AAC 兼容音频。")
            return DownloadPlan(selected, listOf(part(video, "video-${item.cid}-${selected.quality}")), audio?.let { part(it, "audio-${it.getInt("id")}") })
        }
        return DownloadPlan(selected, data.getJSONArray("durl").objects().mapIndexed { i, v -> StreamPart("progressive-${item.cid}-${selected.quality}-$i", v.getString("url"), v.optJSONArray("backup_url").strings()) }, null)
    }
}
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
