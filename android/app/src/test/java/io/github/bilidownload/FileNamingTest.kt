package io.github.bilidownload
import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson
class FileNamingTest {
    private fun item(count: Int = 1, index: Int = 1, part: String = "标题") = MediaItem("video:123", "标题", part, "video", 1, "BV1xx411c7mD", 123, 0, index, partCount = count)
    @Test fun defaults() {
        assertEquals("标题.mp4", FileNaming.render(item()))
        assertEquals("标题 P2 第二部分.mp4", FileNaming.render(item(3,2,"第二部分")))
        assertEquals("标题 P1.mp4", FileNaming.render(item(3)))
        assertEquals("标题 P4 第四集.mp4", FileNaming.render(item(12,4,"第四集").copy(kind="bangumi")))
    }
    @Test fun templatesAndOverrides() {
        assertEquals("BV1xx411c7mD-2-第二部分.mp4", FileNaming.render(item(3,2,"第二部分"), "{bvid}-{index}-{part}"))
        assertEquals("我的视频.mp4", FileNaming.render(item(), "{title}", "我的视频.MP4"))
    }
    @Test fun invalidTemplates() { listOf("", "{unknown}", "{title", "title}").forEach { assertTrue(runCatching { FileNaming.validate(it) }.isFailure) } }
    @Test fun sanitizesAndBounds() {
        assertEquals("_CON.mp4", FileNaming.normalize("CON"))
        assertEquals("a_b_c_.mp4", FileNaming.normalize("a/b:c?. "))
        assertEquals("video.mp4", FileNaming.normalize("..."))
        val value = FileNaming.normalize("中😀".repeat(80)).removeSuffix(".mp4")
        assertEquals(100, value.codePointCount(0,value.length))
    }
    @Test fun collisions() { assertEquals("视频 (2).mp4", FileNaming.candidate("视频.mp4", 2)) }
    @Test fun oldAndNewTaskSnapshots() {
        val gson = Gson()
        val entry = DownloadEntry("task", item(), 80, outputFileName="自定义.mp4")
        assertEquals(entry, gson.fromJson(gson.toJson(entry), DownloadEntry::class.java))
        val old = gson.toJsonTree(entry).asJsonObject.apply { remove("outputFileName"); remove("finalFileName") }
        assertNull(gson.fromJson(old, DownloadEntry::class.java).outputFileName)
    }
}
