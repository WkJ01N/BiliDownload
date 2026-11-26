package io.github.bilidownload

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class GifIntegrationTest {
    @Test fun convertsAnimationToLoopingGif(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "gif-qa-" + UUID.randomUUID()).apply { mkdir() }
        try {
            val source = File(directory, "animation.mp4")
            instrumentation.context.assets.open(source.name).use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            val destination = File(directory, "animation.gif")
            AudioProcessor(context).convertGif(source, destination)
            val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(destination))
            assertTrue(decoded is AnimatedImageDrawable)
            assertEquals(64, decoded.intrinsicWidth)
            assertEquals(48, decoded.intrinsicHeight)
            assertEquals(AnimatedImageDrawable.REPEAT_INFINITE, (decoded as AnimatedImageDrawable).repeatCount)
        } finally {
            directory.deleteRecursively()
        }
    }
}
