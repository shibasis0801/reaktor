package dev.shibasis.reaktor.tooling.database

data class PrefixEntry(val key: String, val bytes: Long? = null, val expiresAtSeconds: Long? = null)
data class PrefixGroup(val prefix: String, val count: Int, val knownBytes: Long, val unknownSizes: Int)
data class PrefixInventorySnapshot(val prefix: String, val groups: List<PrefixGroup>, val count: Int,
    val knownBytes: Long, val unknownSizes: Int, val expiringKeys: Int, val pages: Int, val complete: Boolean,
    val cursor: String?)

/** Bounded metadata inventory; duplicate keys update their observation instead of inflating totals. */
class PrefixInventory(val prefix: String) {
    private val entries = linkedMapOf<String, PrefixEntry>()
    private val cursors = mutableSetOf<String>()
    private var pages = 0
    private var cursor: String? = null
    private var complete = false
    init { require(prefix.length <= 4096 && '\u0000' !in prefix) }

    fun addPage(page: List<PrefixEntry>, nextCursor: String?, truncated: Boolean) {
        require(!complete && page.size <= 500)
        require(!truncated || !nextCursor.isNullOrBlank()) { "Truncated inventory has no continuation cursor" }
        require(nextCursor == null || nextCursor !in cursors) { "Provider repeated an inventory cursor" }
        require(page.all { it.key.startsWith(prefix) && it.key.length <= 4096 && (it.bytes == null || it.bytes >= 0) })
        val next = entries.toMutableMap().apply { page.forEach { put(it.key, it) } }
        next.values.fold(0L) { total, entry ->
            require((entry.bytes ?: 0) <= Long.MAX_VALUE - total) { "Observed sizes overflow the byte counter" }
            total + (entry.bytes ?: 0)
        }
        require(next.size <= 10_000 && next.keys.sumOf { it.encodeToByteArray().size.toLong() } <= 8_388_608 && pages < 100) {
            "Inventory budget reached: 10,000 keys, 100 pages or 8 MiB of key names. Narrow the prefix."
        }
        entries.clear(); entries.putAll(next)
        cursor = nextCursor.takeIf { truncated }
        cursor?.let { cursors += it }
        complete = !truncated
        pages++
    }

    fun snapshot(): PrefixInventorySnapshot {
        val groups = entries.values.groupBy { entry ->
            val remainder = entry.key.removePrefix(prefix)
            if ('/' in remainder) prefix + remainder.substringBefore('/') + "/" else prefix
        }.map { (name, values) -> PrefixGroup(name, values.size, values.sumOf { it.bytes ?: 0 }, values.count { it.bytes == null }) }
            .sortedWith(compareByDescending<PrefixGroup> { it.knownBytes }.thenByDescending { it.count }.thenBy { it.prefix })
        return PrefixInventorySnapshot(prefix, groups, entries.size, entries.values.sumOf { it.bytes ?: 0 },
            entries.values.count { it.bytes == null }, entries.values.count { it.expiresAtSeconds != null }, pages, complete, cursor)
    }
}
