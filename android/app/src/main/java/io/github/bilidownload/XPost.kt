package io.github.bilidownload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

data class XLink(val id: String, val index: Int? = null, val selector: String? = null) {
    val url get() = "https://x.com/i/status/$id"

    companion object {
        private val hosts = setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com")
        private val status = Regex("/(?:[A-Za-z0-9_]{1,15}/status(?:es)?|i/web/status)/([1-9][0-9]{0,19})(?:/(photo|video)/([1-9][0-9]?))?/?")
        private fun uri(text: String) = runCatching { URI(text) }.getOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && it.port == -1 && it.userInfo == null
        }
        fun parse(text: String): XLink? {
            val url = uri(text)?.takeIf { it.host?.lowercase() in hosts } ?: return null
            val match = status.matchEntire(url.rawPath.orEmpty()) ?: return null
            return XLink(match.groupValues[1], match.groupValues[3].toIntOrNull(),match.groupValues[2].ifEmpty { null })
        }
        fun short(text: String): Boolean = uri(text)?.let { it.host.equals("t.co", true) && it.rawPath.matches(Regex("/[A-Za-z0-9]{1,64}/?")) } == true
        suspend fun expand(text: String): XLink = withContext(Dispatchers.IO) {
            var current = URI(text)
            val seen = mutableSetOf<URI>()
            repeat(3) {
                if(!short(current.toString()) || !seen.add(current)) throw BiliException("短链接未指向 X 帖子。")
                val conn = URI("https://t.co" + current.rawPath).toURL().openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 3000; conn.readTimeout = 3000; conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    if(code !in listOf(301,302,303,307,308)) throw BiliException("无法展开 t.co 链接。")
                    val next = conn.getHeaderField("Location") ?: throw BiliException("短链接缺少跳转地址。")
                    current = current.resolve(next)
                    if(current.scheme != "https") throw BiliException("短链接未指向安全的帖子链接。")
                } finally { conn.disconnect() }
                parse(current.toString())?.let { return@withContext it }
            }
            throw BiliException("短链接跳转次数过多。")
        }
    }
}

data class XDocument(val author: String, val handle: String, val text: String, val createdAt: String, val url: String,
                     val quote: String?, val truncated: Boolean) {
    fun markdown(names: List<Pair<String,String>>): String = buildString {
        appendLine("$author (@$handle)"); appendLine(); appendLine(createdAt); appendLine(); appendLine(url)
        if(text.isNotEmpty()) { appendLine(); appendLine(text) }
        if(truncated) { appendLine(); appendLine("正文可能不完整，请查看原帖。") }
        quote?.let { appendLine(); appendLine("引用帖子：$it") }
        for((name,mime) in names) {
            val link = URLEncoder.encode(name,"UTF-8").replace("+","%20")
            val label = name.replace("\\","\\\\").replace("[","\\[").replace("]","\\]")
            appendLine(); if(mime.startsWith("image/")) append("!"); appendLine("[$label]($link)")
        }
    }
}
data class XContent(val postId: String, val handle: String, val attachment: String?, val kind: String,
                    val extension: String, val bytes: Long?, val variants: List<Variant>, val audio: Boolean,
                    val duration: Double, val document: XDocument? = null) {
    val video get() = kind == "video" || kind == "gif"
    val available get() = !video || variants.isNotEmpty()
    companion object {
        fun catalog(json: JSONObject, link: XLink): YoutubeCatalog {
            val post = json.getString("id")
            val doc = XDocument(json.getString("author"), json.getString("handle").trimStart('@'), json.getString("text"),
                json.optString("createdAt"), json.getString("url"), json.optString("quotedUrl").takeIf { XLink.parse(it) != null }, json.optBoolean("truncated"))
            val rows = json.getJSONArray("items").objects()
            val fallback = "@"+doc.handle+" "+post
            val title = doc.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: fallback
            val media = rows.map { raw ->
                val type = raw.getString("kind")
                val ext = raw.getString("extension")
                require(ext in setOf(".jpg",".jpeg",".png",".webp",".mp4"))
                val variants = raw.optJSONArray("variants").objects().map { v ->
                    val height = v.getInt("height")
                    Variant(height, height.toString()+"p", v.optLong("bytes").takeIf { it > 0 }, v.getString("format"))
                }.sortedBy { it.quality }
                val id = raw.getString("id")
                val order = raw.getInt("index")
                val label = when(type) { "gif" -> "动图"; "image" -> "图片"; "video" -> "视频"; else -> error("Unexpected attachment") }
                val content = XContent(post,doc.handle,id,type,ext,raw.optLong("bytes").takeIf { it > 0 },variants,raw.optBoolean("audio"),raw.optDouble("duration",0.0))
                val preview = raw.optString("thumbnail").takeIf {
                    runCatching { URI(it).let { uri -> uri.scheme == "https" && uri.host == "pbs.twimg.com" && uri.port == -1 && uri.userInfo == null } }.getOrDefault(false)
                }.orEmpty()
                MediaItem("x:$post:$id",title,"$label $order","x",0,"",0,0,order,cover=preview,partCount=rows.size,x=content)
            }
            val body = MediaItem("x:$post:text",title,"帖子正文","x",0,"",0,0,0,
                x=XContent(post,doc.handle,null,"text",".md",null,emptyList(),false,0.0,doc))
            val available = media.filter { it.x!!.available }
            return YoutubeCatalog(media + body, defaults = when {
                link.index != null -> listOf((available.firstOrNull { it.index == link.index && (if(link.selector == "photo") it.x?.kind == "image" else it.x?.video == true) } ?: throw BiliException("帖子中没有指定的附件。")).id)
                available.isEmpty() -> listOf(body.id)
                else -> available.map { it.id }
            })
        }
    }
}
data class XDestination(val grouped: Boolean, val tree: String?, val dependencies: List<String> = emptyList())
fun xMime(extension: String): String = when(extension.lowercase()) {
    ".jpg",".jpeg" -> "image/jpeg"; ".png" -> "image/png"; ".webp" -> "image/webp"; ".gif" -> "image/gif"
    ".md" -> "text/markdown"; else -> "video/mp4"
}
fun DownloadEntry.waitsForAttachments(rows: List<DownloadEntry>): Boolean {
    val dependencies = xDestination?.dependencies.orEmpty()
    return dependencies.isNotEmpty() && rows.any { it.id in dependencies && it.state in setOf("Queued","Downloading","Merging") }
}
val DownloadEntry.mime get() = audioOutput?.mime ?: xMime(outputFileName?.substringAfterLast('.')?.let { ".$it" } ?: ".mp4")
