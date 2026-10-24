package io.github.bilidownload
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class RedirectResponse(val status: Int, val location: String?)
class ShortLinks(private val request: suspend (String) -> RedirectResponse, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val gate = Mutex()
    private val cache = linkedMapOf<String, Pair<String, Long>>()
    suspend fun expand(original: String): String = gate.withLock {
        var url = original.replaceFirst("http://", "https://")
        if (!URI(url).host.equals("b23.tv", true)) return@withLock url
        cache[url]?.takeIf { it.second > clock() }?.let { return@withLock it.first }
        val key = url
        val visited = mutableSetOf<String>()
        withTimeout(10_000) {
            var hops = 0
            while (URI(url).host.equals("b23.tv", true)) {
                if(!Links.allowed(url)) throw BiliException("短链接跳转到不支持的网站。")
                if(!visited.add(url)) throw BiliException("短链接发生循环跳转。")
                if(hops++ >= 6) throw BiliException("短链接跳转次数过多。")
                val response = request(url)
                if(response.status !in 300..399 || response.location == null) throw BiliException("短链接无法跳转，请使用完整视频链接。")
                url = URI(url).resolve(response.location).toString().replaceFirst("http://", "https://")
            }
            if(!Links.allowed(url)) throw BiliException("短链接跳转到不支持的网站。")
            if(cache.size >= 128) cache.remove(cache.keys.first())
            cache[key] = url to clock() + 600_000
            url
        }
    }
}
