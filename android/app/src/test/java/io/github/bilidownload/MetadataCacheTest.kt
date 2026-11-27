package io.github.bilidownload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MetadataCacheTest {
    @Test fun cachesSuccessfulLookupsAndHonorsExpiryAndAccountGeneration(): Unit = runBlocking {
        var time = 0L
        val cache = MetadataCache<Pair<String, Long>, String>(lifetimeNanos = 10, capacity = 2, clock = { time })
        var calls = 0
        suspend fun lookup(id: String, revision: Long) = cache.load(id to revision) { "entry ${++calls}" }
        assertEquals("entry 1", lookup("a", 0))
        assertEquals("entry 1", lookup("a", 0))
        assertEquals("entry 2", lookup("a", 1))
        time = 10
        assertEquals("entry 3", lookup("a", 0))
        assertTrue(runCatching { cache.load("b" to 0L) { error("network") } }.isFailure)
        assertEquals("entry 4", lookup("b", 0))
    }

    @Test fun batchesMetadataAndPreservesInputOrder(): Unit = runBlocking {
        withTimeout(3000) {
            val firstGroup = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var started = 0
            val batch = async {
                (1..7).toList().mapMetadata { item ->
                    started++
                    if (started == 3) firstGroup.complete(Unit)
                    finish.await()
                    item * 2
                }
            }
            firstGroup.await()
            assertEquals(3, started)
            finish.complete(Unit)
            assertEquals((1..7).map { it * 2 }, batch.await())
        }
    }
}
