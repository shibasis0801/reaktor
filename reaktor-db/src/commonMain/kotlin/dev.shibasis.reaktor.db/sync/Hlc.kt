package dev.shibasis.reaktor.db.sync

/**
 * A hybrid logical clock reading: wall time, a counter for events within the same millisecond (or
 * while the wall clock stands still or runs backwards), and the device that made it.
 *
 * Last-write-wins needs an order every device agrees on. Wall time alone is not one: two phones a
 * minute apart in clock skew would let the stale one win every race for that minute, and a device
 * whose clock is put back would lose to its own earlier writes. An HLC never runs backwards on one
 * device, moves past anything it has seen from another, and stays within a hair of real time.
 *
 * [toString] is the wire and storage form, and it sorts as text in the same order as the values
 * compare, so a server can order and compare readings with a plain string comparison.
 */
data class Hlc(val wallMs: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    init {
        require(wallMs >= 0) { "wallMs must not be negative" }
        require(counter in 0..MAX_COUNTER) { "counter out of range" }
    }

    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wallMs, Hlc::counter, Hlc::node)

    override fun toString(): String =
        wallMs.toString().padStart(WALL_DIGITS, '0') + SEPARATOR +
            counter.toString().padStart(COUNTER_DIGITS, '0') + SEPARATOR + node

    companion object {
        const val MAX_COUNTER = 999_999
        private const val WALL_DIGITS = 15
        private const val COUNTER_DIGITS = 6
        private const val SEPARATOR = '.'

        fun parse(text: String): Hlc? {
            val first = text.indexOf(SEPARATOR)
            val second = text.indexOf(SEPARATOR, first + 1)
            if (first != WALL_DIGITS || second != WALL_DIGITS + 1 + COUNTER_DIGITS) return null
            val wall = text.substring(0, first).toLongOrNull() ?: return null
            val counter = text.substring(first + 1, second).toIntOrNull() ?: return null
            val node = text.substring(second + 1)
            if (node.isEmpty()) return null
            return runCatching { Hlc(wall, counter, node) }.getOrNull()
        }
    }
}

/**
 * Issues [Hlc] readings for one device. Not thread-safe: [Sync] calls it under its own lock.
 */
class HlcClock(
    private val node: String,
    private val now: () -> Long,
) {
    private var wall = 0L
    private var counter = 0

    /** A reading for something that happened here, later than every reading seen so far. */
    fun tick(): Hlc {
        val time = now()
        if (time > wall) {
            wall = time
            counter = 0
        } else {
            counter++
        }
        carry()
        return Hlc(wall, counter, node)
    }

    /** Takes in a reading from elsewhere, so the next [tick] is later than it. */
    fun receive(remote: Hlc) {
        val time = now()
        val next = maxOf(wall, remote.wallMs, time)
        counter = when {
            next == wall && next == remote.wallMs -> maxOf(counter, remote.counter) + 1
            next == wall -> counter + 1
            next == remote.wallMs -> remote.counter + 1
            else -> 0
        }
        wall = next
        carry()
    }

    // A million events in one millisecond is not a real workload, but a clock that throws is worse
    // than one that borrows a millisecond from the future.
    private fun carry() {
        if (counter > Hlc.MAX_COUNTER) {
            wall += 1
            counter = 0
        }
    }
}
