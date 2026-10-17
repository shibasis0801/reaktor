package dev.shibasis.reaktor.devtools

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class EncodedFrame(val bytes: ByteArray, val width: Int, val height: Int)

expect fun ImageBitmap.encodeFrame(maxDimension: Int, quality: Int): EncodedFrame?

class ScreenFeed(
    private val agent: DevToolsAgent,
    private val layer: GraphicsLayer,
    private val density: Float,
    private val tree: suspend () -> List<SemanticsNodeFact>,
    private val framesPerSecond: Int = 8,
    private val maxDimension: Int = 1280,
) {
    private val drawn = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val sendTree = atomic(true)
    private var lastTree: List<SemanticsNodeFact>? = null

    fun onDraw() {
        if (agent.screen.subscribers.value > 0) drawn.tryEmit(Unit)
    }

    fun refresh() {
        sendTree.value = true
        drawn.tryEmit(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        val changes = Snapshot.registerApplyObserver { _, _ -> onDraw() }
        try {
            drawn.conflate().collect {
                delay(SettleMillis)
                if (agent.screen.subscribers.value > 0) runCatching { capture() }
                delay(1000L / framesPerSecond)
            }
        } finally {
            changes.dispose()
        }
    }

    private suspend fun capture() {
        val nodes = tree()
        val bitmap = layer.readPixels() ?: return
        val frame = bitmap.encodeFrame(maxDimension, 80) ?: return
        val changed = sendTree.getAndSet(false) || nodes != lastTree
        lastTree = nodes
        val encoded = frame.bytes.encodeBase64()
        agent.screen.emit { sequence, nanos ->
            AgentFact.Screen(
                sequence = sequence,
                monotonicNanos = nanos,
                widthPixels = bitmap.width,
                heightPixels = bitmap.height,
                imageWidth = frame.width,
                imageHeight = frame.height,
                jpegBase64 = encoded,
                density = density,
                nodes = nodes.takeIf { changed },
            )
        }
    }
}

private const val SettleMillis = 48L
