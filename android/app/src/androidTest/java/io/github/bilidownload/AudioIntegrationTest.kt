package io.github.bilidownload

import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.UUID

@RunWith(Parameterized::class)
class AudioIntegrationTest(private val sample: String, private val codec: String, private val rate: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, {1}, {2} kb/s")
        fun cases() = listOf(
            arrayOf("aac.m4a", "AAC", 0),
            arrayOf("opus.webm", "Opus", 0),
            arrayOf("lossless.m4a", "FLAC", 0),
            arrayOf("dolby.m4a", "E-AC-3", 0),
            arrayOf("mixed.mp4", "AAC", 0),
            arrayOf("dolby.m4a", "E-AC-3", 320),
            arrayOf("opus.webm", "Opus", 192),
            arrayOf("aac.m4a", "AAC", 128)
        )
    }

    @Test fun exportAndPublish(): Unit = runBlocking(Dispatchers.IO) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val engine = BiliApplication.engine
        val item = MediaItem("test", "试听", "试听", "video", 0, "", 0, 0, 1)
        val dir = File(ctx.cacheDir, "audio-qa-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val source = File(dir, sample)
            inst.context.assets.open(sample).use { input -> source.outputStream().use { input.copyTo(it) } }
            val track = AudioTrack(sample, codec, 0, "", if (codec == "E-AC-3") 6 else 2, 1.0,
                sample == "mixed.mp4", source.length(), null, MusicTags("试听", "Artist", "Album"))
            val selected = AudioOutput(track, rate)
            val fileName = "$sample-$rate" + selected.extension
            val out = File(dir, fileName)
            engine.exportAudio(listOf(source), out, selected, item)
            val uri = engine.publish(out, "BiliDownload-QA-" + fileName, selected.mime)
            try {
                assertEquals("media", uri.authority)
                assertTrue(uri.path!!.contains("/audio/"))
                val resolver = ctx.contentResolver
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Music/BiliDownload/", it.getString(0).trimEnd('/') + "/")
                }
                resolver.openFileDescriptor(uri, "r")!!.use { fd ->
                    val tags = TagLib.getMetadata(fd.dup().detachFd(), false)!!.propertyMap
                    assertEquals("试听", tags["TITLE"]?.single())
                    assertEquals("Artist", tags["ARTIST"]?.single())
                    assertEquals("Album", tags["ALBUM"]?.single())
                }
                if (InstrumentationRegistry.getArguments().getString("verifyFiles") == "true") {
                    // The host probes the encoded file, including codecs the platform extractor decodes to PCM.
                    val exports = File(ctx.cacheDir, "qa-audio-exports").apply { mkdirs() }
                    resolver.openInputStream(uri)!!.use { input ->
                        File(exports, fileName).outputStream().use { output -> input.copyTo(output) }
                    }
                }
            } finally { ctx.contentResolver.delete(uri, null, null) }
        } finally { dir.deleteRecursively() }
    }
}
