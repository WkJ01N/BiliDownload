package io.github.bilidownload

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class MediaComponentsTest {
    @get:Rule val folder = TemporaryFolder()

    private val data = "verified archive".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val verifier = VerifiedComponentArchive(data.size.toLong(), sha)

    @Test fun publishesOnlyAfterVerification() {
        val destination = folder.root.resolve("engine/yt-dlp")
        verifier.install(data.inputStream(), destination) {
            assertFalse(destination.exists())
            assertEquals(data.size.toLong(), it)
        }
        assertTrue(verifier.verified(destination))
        assertArrayEquals(data, destination.readBytes())
    }

    @Test fun rejectsIncorrectLengthAndTamperedBytesWithoutReplacingTheArchive() {
        val destination = folder.newFile("yt-dlp")
        destination.writeBytes(data)
        for (invalid in listOf(data.copyOf(1), data.copyOf(data.size + 1), data.reversedArray())) {
            try {
                verifier.install(invalid.inputStream(), destination) {}
                fail("Invalid archive was accepted")
            } catch (_: ComponentIntegrityException) {
                assertArrayEquals(data, destination.readBytes())
                assertArrayEquals(arrayOf(destination.name), folder.root.list())
            }
        }
    }

    @Test fun interruptedInstallLeavesNothingPublished() {
        val destination = folder.root.resolve("yt-dlp")
        try {
            verifier.install(data.inputStream(), destination) { throw kotlinx.coroutines.CancellationException() }
            fail("Cancellation was not propagated")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertFalse(destination.exists())
            assertTrue(folder.root.list()!!.isEmpty())
        }
    }

    @Test fun removesOnlyRetiredResolverVersions() {
        val current = "a".repeat(64)
        val outdated = folder.newFolder("components", "media", "b".repeat(64))
        val root = outdated.parentFile!!
        val active = root.resolve(current).apply { mkdir() }.resolve("yt-dlp").apply { writeText("current") }
        val unrelated = root.resolve("future-layout")
        unrelated.writeText("other")
        val userData = folder.newFile("tasks.db")

        pruneComponentArchives(root, current)

        assertFalse(outdated.exists())
        assertEquals("current", active.readText())
        assertEquals("other", unrelated.readText())
        assertTrue(userData.isFile)
    }
}
