package io.github.bilidownload
import org.junit.Assert.*
import org.junit.Test
class LinksTest {
    @Test fun extractsShareText() { assertEquals(listOf("https://b23.tv/abcd"), Links.extract("视频分享 https://b23.tv/abcd。")) }
    @Test fun rejectsLookalikeHosts() { assertTrue(Links.extract("https://bilibili.com.attacker.test/video/BV1xx411c7mD").isEmpty()) }
    @Test fun deduplicates() { assertEquals(1, Links.extract("https://b23.tv/a https://b23.tv/a").size) }
    @Test fun matchesWbiVector() { assertEquals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4", BiliApi.sign(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"), "7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45", 1702204169)) }
}
