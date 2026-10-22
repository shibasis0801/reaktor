package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

@Composable
internal expect fun Prefetching(content: @Composable () -> Unit)

internal class FramePrefetch(private val scope: CoroutineScope, private val after: (() -> Unit) -> Unit) {
    private val high = ArrayDeque<() -> Boolean>()
    private val low = ArrayDeque<() -> Boolean>()
    private var deadline = TimeSource.Monotonic.markNow()
    private var interval = 16_666_667L
    private var running = false

    fun left() = -deadline.elapsedNow().inWholeNanoseconds

    fun schedule(urgent: Boolean, request: () -> Boolean) {
        (if (urgent) high else low).addLast(request)
        if (running) return
        running = true
        scope.launch {
            try {
                var last = 0L
                while (high.isNotEmpty() || low.isNotEmpty()) withFrameNanos { frame ->
                    if (frame - last in 4_000_000L..50_000_000L) interval = frame - last
                    last = frame
                    deadline = TimeSource.Monotonic.markNow() + (interval - 1_000_000L).nanoseconds
                    after(::drain)
                }
            } finally { running = false }
        }
    }

    private fun drain() {
        while (left() > 0) {
            val queue = if (high.isNotEmpty()) high else if (low.isNotEmpty()) low else return
            if (queue.first()()) return
            queue.removeFirst()
        }
    }
}
