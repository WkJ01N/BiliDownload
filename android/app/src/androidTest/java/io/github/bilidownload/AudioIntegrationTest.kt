package io.github.bilidownload

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioIntegrationTest {
    @Test fun nativeExportCodecExtensionTagsAndMusicCollection() = runBlocking(Dispatchers.IO) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val engine = BiliApplication.engine
        val dir = File(ctx.cacheDir,"audio-qa-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val item = MediaItem("test","试听","试听","video",0,"",0,0,1)
        try {
            val cases = listOf(Triple("aac.m4a","AAC",0), Triple("opus.webm","Opus",0), Triple("lossless.m4a","FLAC",0),
                Triple("dolby.m4a","E-AC-3",0), Triple("mixed.mp4","AAC",0)) + listOf(128,192,320).map { Triple("opus.webm","Opus",it) }
            for((sample,codec,rate) in cases) {
                val src = File(dir,sample)
                if(!src.exists()) inst.context.assets.open(sample).use { input -> src.outputStream().use { input.copyTo(it) } }
                val track = AudioTrack(sample,codec,0,"",if(codec == "E-AC-3") 6 else 2,1.0,sample == "mixed.mp4",src.length(),null,MusicTags("试听","Artist","Album"))
                val chosen = AudioOutput(track,rate); val out = File(dir,"output-$rate" + chosen.extension)
                engine.exportAudio(listOf(src),out,chosen,item)
                assertTrue(out.length() > 0)
                ParcelFileDescriptor.open(out,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val tags = TagLib.getMetadata(fd.dup().detachFd(),false)!!
                    assertEquals("Artist",tags.propertyMap["ARTIST"]?.single())
                    assertEquals("Album",tags.propertyMap["ALBUM"]?.single())
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(out.path); assertEquals(1,extractor.trackCount)
                    val fmt = extractor.getTrackFormat(0)
                    val expected = when { rate > 0 -> "audio/mpeg"; codec == "FLAC" -> "audio/flac"; codec == "Opus" -> "audio/opus"; codec == "E-AC-3" -> "audio/eac3"; else -> "audio/mp4a-latm" }
                    assertEquals(expected,fmt.getString(MediaFormat.KEY_MIME))
                } finally { extractor.release() }
                val uri = engine.publish(out,"BiliDownload-QA-" + out.name,chosen.mime)
                try { assertEquals("media",uri.authority); assertTrue(uri.path!!.contains("/audio/")) }
                finally { ctx.contentResolver.delete(uri,null,null) }
            }
        } finally { dir.deleteRecursively() }
    }
}
