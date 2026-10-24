package io.github.bilidownload

data class MediaItem(val id: String, val title: String, val partTitle: String, val kind: String,
    val aid: Long, val bvid: String, val cid: Long, val episodeId: Long, val index: Int, val cover: String = "", val partCount: Int = 1)
data class Variant(val quality: Int, val label: String, val estimatedBytes: Long? = null)
data class StreamPart(val key: String, val url: String, val backups: List<String>)
data class DownloadPlan(val variant: Variant, val video: List<StreamPart>, val audio: StreamPart?)
data class DownloadEntry(val id: String, val item: MediaItem, val quality: Int,
    val state: String = "Queued", val progress: Float = 0f, val message: String = "等待下载", val uri: String? = null, val failureKind: String? = null, val outputFileName: String? = null, val finalFileName: String? = null)
enum class BiliErrorKind { Unknown, LoginRequired, RequestRestricted }
enum class SessionState { Guest, Saved, Authenticated, Expired }
enum class LoginOutcome { Success, Canceled, Failed }
data class LoginResult(val outcome: LoginOutcome, val message: String? = null)
class BiliException(message: String, val kind: BiliErrorKind = BiliErrorKind.Unknown, val httpStatus: Int? = null, val apiCode: Int? = null) : Exception(message)
object AuthRecovery {
    fun loginMessage(result: LoginResult, forTask: Boolean = false): String? = when(result.outcome) {
        LoginOutcome.Success -> if(forTask) "已登录，请点击“继续 / 重试”。" else "已登录，请重新解析。"
        LoginOutcome.Failed -> result.message ?: "登录失败，请重试。"
        LoginOutcome.Canceled -> null
    }
    fun needsLogin(kind: String?, state: SessionState) = kind == BiliErrorKind.LoginRequired.name || kind == BiliErrorKind.RequestRestricted.name && state != SessionState.Authenticated
    fun message(kind: BiliErrorKind, state: SessionState) = when {
        kind == BiliErrorKind.LoginRequired -> "登录信息已失效或此操作需要登录，请登录 B 站后重试。"
        kind == BiliErrorKind.RequestRestricted && state == SessionState.Authenticated -> "B 站暂时限制了当前请求，请稍后重试。重新登录不一定能解除限制。"
        kind == BiliErrorKind.RequestRestricted -> "B 站暂时限制了当前请求。你可以先登录 B 站再重试，登录不保证解除所有请求限制。"
        else -> "请求失败，请稍后重试。"
    }
}

object Links {
    private val pattern = Regex("https?://(?:[a-zA-Z0-9-]+\\.)*(?:bilibili\\.com|b23\\.tv)(?=[/?#\\s，。；！？、）】]|$)[^\\s<>\"，。；！？、）】]*", RegexOption.IGNORE_CASE)
    fun allowed(url: String): Boolean = runCatching {
        val uri = java.net.URI(url); val host = uri.host.orEmpty().lowercase()
        uri.scheme in listOf("http", "https") && uri.port == -1 && (host == "b23.tv" || host == "bilibili.com" || host.endsWith(".bilibili.com"))
    }.getOrDefault(false)
    fun extract(text: String): List<String> {
        val links = pattern.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.filter(::allowed).distinct().toList()
        if (links.isNotEmpty() || text.contains("://")) return links
        return Regex("(?<![a-zA-Z0-9])(?:BV[a-zA-Z0-9]{10}|av[0-9]+)(?![a-zA-Z0-9])").findAll(text)
            .map { "https://www.bilibili.com/video/${it.value}" }.distinct().toList()
    }
    fun safeName(name: String) = name.replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").trim().trimEnd('.').take(100).ifBlank { "video" }
}

data class AccountInfo(val state: SessionState, val username: String? = null) {
    val loggedIn: Boolean get() = state == SessionState.Saved || state == SessionState.Authenticated
    val label: String get() = if(loggedIn) username?.takeIf { it.isNotBlank() } ?: "已登录" else if(state == SessionState.Expired) "登录已过期" else "未登录"
}
