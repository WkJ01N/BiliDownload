package io.github.bilidownload

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Holds successful display metadata in memory. Media URLs are resolved again for transfers. */
internal class MetadataCache<K, V : Any>(
    private val lifetimeNanos: Long = 90_000_000_000L,
    private val capacity: Int = 64,
    private val clock: () -> Long = System::nanoTime
) {
    private data class Entry<V>(val value: V, val savedAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)

    suspend fun load(key: K, fetch: suspend () -> V): V {
        synchronized(entries) {
            val existing = entries[key]
            if (existing != null && clock() - existing.savedAt < lifetimeNanos) return existing.value
            entries.remove(key)
        }
        val value = fetch()
        synchronized(entries) {
            entries[key] = Entry(value, clock())
            if (entries.size > capacity) entries.remove(entries.keys.first())
        }
        return value
    }
}

internal suspend fun <T, R> List<T>.mapMetadata(transform: suspend (T) -> R): List<R> = coroutineScope {
    val slots = Semaphore(3)
    map { value -> async { slots.withPermit { transform(value) } } }.awaitAll()
}
