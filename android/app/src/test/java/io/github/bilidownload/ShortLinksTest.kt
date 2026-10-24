package io.github.bilidownload
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
class ShortLinksTest {
    @Test fun cachedRedirectRetainsPageAndExpires() = runBlocking {
        var calls=0; var time=0L
        val resolver=ShortLinks(request={ calls++; RedirectResponse(302,"https://www.bilibili.com/video/BV1xx411c7mD?p=2") }, clock={time})
        repeat(2) { assertTrue(resolver.expand("https://b23.tv/link").endsWith("p=2")) }; assertEquals(1,calls)
        time=600001; resolver.expand("https://b23.tv/link"); assertEquals(2,calls)
    }
    @Test fun rejectsLoopsAndExternalHosts() = runBlocking {
        listOf("https://b23.tv/link","https://example.com/video/BV1xx411c7mD").forEach { target ->
            val resolver=ShortLinks(request={ RedirectResponse(302,target) })
            assertTrue(runCatching { resolver.expand("https://b23.tv/link") }.exceptionOrNull() is BiliException)
        }
    }
    @Test fun relativeRedirectAndNoFailedCache() = runBlocking {
        var calls=0
        val resolver=ShortLinks(request={ url -> calls++; if(calls==1) throw BiliException("请求受限",BiliErrorKind.RequestRestricted); RedirectResponse(302,if(url.endsWith("start")) "/end" else "https://www.bilibili.com/bangumi/play/ep2") })
        assertTrue(runCatching { resolver.expand("https://b23.tv/start") }.isFailure)
        assertTrue(resolver.expand("https://b23.tv/start").endsWith("ep2")); assertEquals(3,calls)
    }
    @Test fun propagatesCancellation() = runBlocking {
        val resolver=ShortLinks(request={ delay(10000); RedirectResponse(302,null) })
        val work=async { resolver.expand("https://b23.tv/link") }; yield(); work.cancel()
        assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
    }
}
