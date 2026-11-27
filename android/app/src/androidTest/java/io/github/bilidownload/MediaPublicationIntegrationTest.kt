package io.github.bilidownload

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.system.measureTimeMillis

class MediaPublicationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun copyAsset(work: File, name: String): File = File(work, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun trackTypes(uri: Uri): Set<String?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.toSet()
        } finally { extractor.release() }
    }

    @Test fun skipsUnneededRemuxAndPublishesSplitTracks(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(MediaPublisher(context).selectedTree() == null)
        val id = UUID.randomUUID()
        val work = File(context.cacheDir, "publish-$id").apply { mkdir() }
        val resolver = context.contentResolver
        val saved = mutableListOf<Uri>()
        try {
            val video = copyAsset(work, "animation.mp4")
            val audio = copyAsset(work, "aac.m4a")
            assertTrue(canSaveMp4Directly(video))
            assertTrue(canSaveMp4Directly(audio, audioOnly = true))
            var status = ""
            val direct = BiliApplication.engine.finishVideo(listOf(video), null, work, "QA-video-$id.mp4") { status = it }
            saved += direct
            assertEquals("保存视频", status)
            fun digest(stream: java.io.InputStream) = stream.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
            assertArrayEquals(digest(video.inputStream()), digest(resolver.openInputStream(direct)!!))
            assertFalse(File(work, "merged.mp4").exists())

            val tracks = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC)
            val remux = File(work, "stream-copy.mp4")
            BiliApplication.audio.muxVideo(video, audio, remux)
            assertEquals(tracks, trackTypes(Uri.fromFile(remux)))
            val combined = BiliApplication.engine.finishVideo(listOf(video), audio, work, "QA-video-$id.mp4")
            saved += combined
            assertEquals(tracks, trackTypes(combined))
            val names = saved.map { uri ->
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                }
            }
            assertNotEquals(names.first(), names.last())
        } finally {
            saved.forEach { resolver.delete(it, null, null) }
            work.deleteRecursively()
        }
    }

    @Test fun measuresLongExports(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("longMedia") == "true")
        assumeTrue(MediaPublisher(context).selectedTree() == null)
        val id = UUID.randomUUID()
        val work = File(context.cacheDir, "benchmark-$id").apply { mkdir() }
        val saved = mutableListOf<Uri>()
        try {
            val video = copyAsset(work, "perf-video.mp4")
            val audio = copyAsset(work, "perf-audio.m4a")
            val engine = BiliApplication.engine
            val container = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val staged = File(work, "staged.mp4")
            val stagedMs = measureTimeMillis {
                muxMedia(listOf(video), audio, MediaMuxer(staged.path, container))
                saved += engine.publish(staged, "QA-staged-$id.mp4")
            }
            val streamCopyMs = measureTimeMillis { saved += engine.finishVideo(listOf(video), audio, work, "QA-stream-copy-$id.mp4") }
            assertEquals(trackTypes(saved[0]), trackTypes(saved[1]))

            val tags = MusicTags("Upgrade performance")
            val item = MediaItem("qa", "Upgrade performance", "", "video", 0, "", 0, 0, 1)
            val choice = AudioOutput(AudioTrack("aac", "AAC", 128, "", 2, 600.0, false, audio.length(), null, tags), 0)
            val oldAudio = File(work, "staged.m4a")
            val rawAudio = File(work, "direct.m4a")
            val oldAudioMs = measureTimeMillis {
                muxMedia(listOf(audio), null, MediaMuxer(oldAudio.path, container), audioOnly = true)
                AudioTags.write(oldAudio, tags)
            }
            val rawAudioMs = measureTimeMillis { engine.exportAudio(listOf(audio), rawAudio, choice, item) }
            assertTrue(canSaveMp4Directly(rawAudio, audioOnly = true))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Long export (ms): staged video=$stagedMs, stream copy video=$streamCopyMs, remux audio=$oldAudioMs, direct audio=$rawAudioMs\n")
            })
        } finally {
            saved.forEach { context.contentResolver.delete(it, null, null) }
            work.deleteRecursively()
        }
    }
}
