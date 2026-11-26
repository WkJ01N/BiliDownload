package io.github.bilidownload

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MediaRuntimeIntegrationTest {
    @Test fun packagedPythonLoadsTheBridgeAndComponent() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File.createTempFile("runtime-test-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(archive.outputStream()).use {
                it.putNextEntry(ZipEntry("yt_dlp/__init__.py"))
                it.write("class YoutubeDL: pass\n".toByteArray())
                it.closeEntry()
            }
            val diagnostics = mutableListOf<String>()
            val runtime = LocalMediaEngine(context, requireArchive = { archive }, testDiagnostics = { diagnostics += it })
            try {
                withTimeout(30_000) {
                    runtime.run(JSONObject().put("platform", "x").put("op", "inspect").put("id", "invalid"),
                        null, {}, { code -> BiliException(code) })
                }
                fail("The bridge should reject the invalid post ID")
            } catch (error: BiliException) {
                assertEquals(diagnostics.joinToString("\n"), "InvalidLink", error.message)
            }
        } finally {
            archive.delete()
        }
    }
}
