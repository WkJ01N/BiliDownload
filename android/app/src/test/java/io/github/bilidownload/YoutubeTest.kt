package io.github.bilidownload

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class YoutubeTest {
    @Test fun linksAndPlaylists() {
        for (link in listOf("https://youtu.be/jNQXAC9IVRw", "https://m.youtube.com/shorts/jNQXAC9IVRw", "https://www.youtube.com/watch?v=jNQXAC9IVRw")) {
            assertEquals("jNQXAC9IVRw", YoutubeLink.parse(link)?.video)
            assertEquals(listOf(link), Links.supported("分享 $link"))
            assertTrue(Links.extract(link).isEmpty())
        }
        assertEquals(YoutubeLink(null, "PLtest"), YoutubeLink.parse("https://youtube.com/playlist?list=PLtest"))
        assertNull(YoutubeLink.parse("https://youtube.com.example.org/watch?v=jNQXAC9IVRw"))
    }
    @Test fun liveReplaySharesUseTheWatchIdentity() {
        val share = "https://www.youtube.com/live/N2rr3OXrR8s?si=Wn4JMg9g1UfVRoMg"
        val video = YoutubeLink("N2rr3OXrR8s", null)
        assertEquals(listOf(share), Links.supported("回放 $share"))
        assertEquals(video, YoutubeLink.parse(share))
        assertEquals(video, YoutubeLink.parse("https://m.youtube.com/live/N2rr3OXrR8s/"))
        assertEquals(video.copy(playlist = "PLtest"), YoutubeLink.parse("$share&list=PLtest"))
        for (path in listOf("/live/?list=PLtest", "/live/not-a-video-id", "/live/N2rr3OXrR8s/extra"))
            assertNull(YoutubeLink.parse("https://www.youtube.com$path"))
    }
    @Test fun guestCompatibilityAndQuality() {
        val item = MediaItem("yt:jNQXAC9IVRw", "video", "video", "video", 0, "", 0, 0, 1, youtubeId = "jNQXAC9IVRw")
        val choices = listOf(360, 720, 1080, 1440).map { Variant(it, "${it}p", formatId = "test") }
        assertEquals(1080, preferredQuality(choices, null))
        assertEquals(720, atMost(choices, Variant(64,"720p"), item)?.quality)
        assertEquals("jNQXAC9IVRw.mp4", FileNaming.render(item, "{id}"))
        val old = Gson().fromJson("""{"id":"video:1","quality":80,"item":{"id":"video:1"}}""", DownloadEntry::class.java)
        assertNull(old.item.youtubeId); assertNull(old.formatId)
    }
    @Test fun cookiesStayOnYoutube() {
        val data = "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tok\n.google.com\tTRUE\t/\tTRUE\t0\tOTHER\tdrop\n.youtube.com\tTRUE\t/\tTRUE\t10\tOLD\tdrop"
        assertEquals("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tok\n", YoutubeCookies.filter(data, 20))
    }
}
