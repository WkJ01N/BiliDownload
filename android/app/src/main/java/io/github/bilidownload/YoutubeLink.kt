package io.github.bilidownload

import java.net.URI
import java.net.URLDecoder

data class YoutubeLink(val video: String?, val playlist: String?, val music: Boolean = false) {
    companion object {
        fun parse(text: String): YoutubeLink? = runCatching {
            val uri = URI(text)
            if (uri.scheme !in setOf("http", "https") || uri.port != -1 || uri.userInfo != null) return null
            val query = uri.rawQuery.orEmpty().split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val path = uri.path.trimEnd('/')
            val video = when (uri.host?.lowercase()) {
                "youtu.be" -> path.removePrefix("/")
                "youtube.com", "m.youtube.com", "www.youtube.com", "music.youtube.com" -> when {
                    path == "/watch" -> query["v"]
                    path.startsWith("/shorts/") -> path.removePrefix("/shorts/")
                    path.startsWith("/live/") -> path.removePrefix("/live/")
                    path == "/playlist" -> null
                    else -> return null
                }
                else -> return null
            }
            if (video != null && !video.matches(Regex("[a-zA-Z0-9_-]{11}"))) return null
            val list = query["list"]?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{2,128}")) }
            if (video == null && list == null) null else YoutubeLink(video, list, uri.host.equals("music.youtube.com", true))
        }.getOrNull()
    }
}

object YoutubeCookies {
    const val MAX_BYTES = 128 * 1024
    fun filter(text: String, now: Long = System.currentTimeMillis() / 1000, x: Boolean = false): String {
        val domains = if(x) listOf("twitter.com", "x.com") else listOf("youtube.com")
        val rows = text.lineSequence().mapNotNull { line ->
            val fields = line.removePrefix("#HttpOnly_").split('\t').toMutableList()
            if (fields.size != 7 || fields.any { it.any(Char::isISOControl) }) return@mapNotNull null
            val host = fields[0].trimStart('.').lowercase()
            val expires = fields[4].toLongOrNull() ?: return@mapNotNull null
            if (domains.none { host == it || host.endsWith(".$it") } ||
                fields[1] !in setOf("TRUE", "FALSE") || fields[3] !in setOf("TRUE", "FALSE") ||
                !fields[2].startsWith("/") || fields[5].isEmpty() || fields[6].isEmpty() ||
                (expires != 0L && expires <= now)) return@mapNotNull null
            fields[0] = if (fields[1] == "TRUE") ".$host" else host
            fields.joinToString("\t")
        }.toList()
        if (rows.isEmpty()) throw BiliException(if(x) "文件中没有有效的 X 会话记录。" else "文件中没有有效的 YouTube 会话记录。")
        return "# Netscape HTTP Cookie File\n" + rows.joinToString("\n") + "\n"
    }
}
