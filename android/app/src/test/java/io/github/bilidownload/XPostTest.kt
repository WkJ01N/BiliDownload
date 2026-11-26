package io.github.bilidownload

import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XPostTest {
    private val sample = """{"id":"97531","author":"作者","handle":"sample","text":"第一行\n第二行","createdAt":"2026-11-26T00:00:00Z","url":"https://x.com/sample/status/97531","quotedUrl":"https://x.com/i/status/123","truncated":true,"items":[{"id":"1","index":1,"kind":"image","extension":".png","bytes":200},{"id":"2","index":2,"kind":"gif","extension":".mp4","variants":[{"height":360,"format":"mp4-360"}]}]}"""

    @Test fun linksThroughAllEntryPoints() {
        val first = "https://twitter.com/sample/status/97531/photo/1?s=20"
        val second = "https://t.co/ab123"
        assertEquals(listOf(first,second), Links.supported("帖子 "+first+" 与 "+second))
        assertEquals(XLink("97531",1,"photo"),XLink.parse(first))
        assertEquals("https://x.com/i/status/97531",XLink.parse("https://mobile.twitter.com/i/web/status/97531")!!.url)
        assertEquals(XLink("97531",2,"video"),XLink.parse("https://x.com/i/status/97531/video/2"))
        for(bad in listOf("https://x.com.evil.test/a/status/9","https://x.com@evil.test/a/status/9","https://user@x.com/a/status/9","https://x.com:123/a/status/9","https://x.com/a/status/1/photo/0","https://x.com/sample","https://t.co/../../bad"))
            assertTrue(Links.supported(bad).isEmpty())
    }
    @Test fun selectedAttachmentsAndTextOnly() {
        val raw = JSONObject(sample)
        val post = XContent.catalog(raw,XLink("97531"))
        assertEquals(listOf("x:97531:1","x:97531:2"),post.defaults)
        assertEquals(listOf("x:97531:2"), XContent.catalog(raw,XLink("97531",2,"video")).defaults)
        assertThrows(BiliException::class.java) { XContent.catalog(raw,XLink("97531",2,"photo")) }
        assertEquals("sample-97531-1.png", FileNaming.render(post.items.first(),extension=".png"))
        assertEquals(listOf("x:97531:text"), XContent.catalog(raw.put("items",org.json.JSONArray()),XLink("97531")).defaults)
    }
    @Test fun markdownUsesActualNamesAndEmbedsOnlyImages() {
        val body = XContent.catalog(JSONObject(sample),XLink("97531")).items.last().x!!.document!!
        val content = body.markdown(listOf("图 (1).gif" to "image/gif","片段.mp4" to "video/mp4"))
        assertTrue(content.contains("第一行\n第二行"))
        assertTrue(content.contains("https://x.com/i/status/123"))
        assertTrue(content.contains("正文可能不完整"))
        assertTrue(content.contains("![图 (1).gif](%E5%9B%BE%20%281%29.gif)"))
        assertTrue(content.contains("\n[片段.mp4]("))
        assertFalse(body.markdown(emptyList()).contains("]("))
    }
    @Test fun independentCookies() {
        val rows = ".x.com\tTRUE\t/\tTRUE\t0\tauth_token\tx\n.twitter.com\tTRUE\t/\tTRUE\t0\tct0\tx\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\ty\nx.com.evil.test\tFALSE\t/\tTRUE\t0\tauth_token\tinvalid"
        assertFalse(YoutubeCookies.filter(rows,x=true).contains("SID"))
        assertFalse(YoutubeCookies.filter(rows,x=true).contains("invalid"))
        assertTrue(YoutubeCookies.filter(rows,x=true).contains("ct0"))
        assertFalse(YoutubeCookies.filter(rows).contains("auth_token"))
    }
    @Test fun groupedArchiveWaitsOnlyForRelatedActiveTasks() {
        val media = XContent.catalog(JSONObject(sample),XLink("97531")).items
        val a = DownloadEntry("photo-job",media.first(),0,outputFileName="你好.png")
        val b = DownloadEntry("text-job",media.last(),0,xDestination=XDestination(true,null,listOf(a.id)))
        assertEquals("image/png",a.mime)
        assertTrue(b.waitsForAttachments(listOf(a,b)))
        for (s in listOf("Paused","Failed","Canceled","Completed")) assertFalse(b.waitsForAttachments(listOf(a.copy(state=s))))
        assertFalse(b.waitsForAttachments(emptyList()))
        val previous = Gson().fromJson("""{"id":"old","item":{"id":"original"},"quality":80,"state":"Paused"}""",DownloadEntry::class.java)
        assertNull(previous.xDestination)
        assertNull(previous.item.x)
        assertEquals("video/mp4",previous.mime)
    }
}
