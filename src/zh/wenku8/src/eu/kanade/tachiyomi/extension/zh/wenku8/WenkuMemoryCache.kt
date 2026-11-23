package eu.kanade.tachiyomi.extension.zh.wenku8

/** Access-order cache with independent entry and payload budgets. */
internal class WenkuMemoryCache<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val keepOversized: Boolean = false,
    private val weigh: (V) -> Long,
) {
    private data class Entry<V>(val value: V, val weight: Long)

    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)
    private var weight = 0L

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(key: K, value: V) {
        val size = weigh(value)
        if (size > maxWeight && !keepOversized) return
        remove(key)
        entries[key] = Entry(value, size)
        weight += size
        while ((entries.size > maxEntries || weight > maxWeight) && entries.size > 1) {
            val oldest = entries.values.iterator()
            weight -= oldest.next().weight
            oldest.remove()
        }
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)?.let {
        weight -= it.weight
        it.value
    }
}

/** Approximate UTF-16 payload, including figure URLs. This is a policy weight, not a heap size. */
internal fun WenkuChapterContent.payloadWeight(): Long = 2L * title.length + blocks.sumOf { block ->
    64L + 2L * when (block) {
        is WenkuContentBlock.TextBlock -> block.text.length
        is WenkuContentBlock.ImageBlock -> block.url.length
    }
}
