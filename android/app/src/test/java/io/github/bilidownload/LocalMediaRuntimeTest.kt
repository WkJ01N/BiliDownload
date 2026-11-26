package io.github.bilidownload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalMediaRuntimeTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, ByteArray>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test fun restoresLibraryAliasesAndRuntimeFiles() {
        val elf = ByteArray(256) { it.toByte() }
        val file = archive("usr/lib/libexample.so" to "libexample.so.1".toByteArray(),
            "usr/lib/libexample.so.1" to "libexample.so.1.2".toByteArray(),
            "usr/lib/libexample.so.1.2" to elf, "usr/etc/tls/cert.pem" to "cert".toByteArray())
        val dir = temp.newFolder()
        unpackPython(file, dir)
        val alias = File(dir, "usr/lib/libexample.so")
        assertArrayEquals(elf, alias.readBytes())
        assertTrue(Files.isSameFile(alias.toPath(), File(dir, "usr/lib/libexample.so.1.2").toPath()))
        assertTrue(File(dir, "usr/etc/tls/cert.pem").isFile)
    }

    @Test fun rejectsEscapingPathsAndInvalidLibraryAliases() {
        val bad = listOf("usr/../../escape" to "x", "usr/lib/libexample.so" to "../../escape",
            "usr/lib/libexample.so" to "libexample.so")
        for ((name, content) in bad) {
            val dir = temp.newFolder()
            val file = archive(name to content.toByteArray())
            assertThrows(IllegalArgumentException::class.java) { unpackPython(file, dir) }
            assertFalse(File(temp.root, "escape").exists())
        }
    }
}
