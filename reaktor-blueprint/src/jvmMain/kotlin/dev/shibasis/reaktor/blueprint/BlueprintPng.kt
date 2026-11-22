package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.shibasis.reaktor.blueprint.canvas.Area
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

suspend fun blueprintPng(bounds: Area, canvas: GraphCanvasState, content: @Composable () -> Unit): ByteArray =
    withContext(Dispatchers.Default) {
        val padding = BlueprintEngine.FrameTop
        val width = bounds.width + padding * 2
        val height = bounds.height + padding * 2
        require(listOf(bounds.x, bounds.y, width, height).all { it.isFinite() } && width > 0 && height > 0) { "Graph has no exportable bounds" }
        val pixels = width * height
        require(pixels <= 96_000_000 && width <= 32760 && height <= 32760) { "Graph exceeds PNG dimensions; export a smaller selection" }
        val scale = min(2.0, min(sqrt(96_000_000.0 / pixels), 32760.0 / maxOf(width, height))).toFloat()
        canvas.restore(padding - bounds.x, padding - bounds.y, 1.0)
        val scene = ImageComposeScene(ceil(width * scale).toInt(), ceil(height * scale).toInt(), Density(scale),
            coroutineContext = Dispatchers.Unconfined, content = content)
        try {
            var clock = 0L
            repeat(6) {
                coroutineContext.ensureActive()
                Snapshot.sendApplyNotifications()
                clock += 100_000_000L
                scene.render(clock).close()
            }
            scene.render(clock + 1).use { image ->
                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "Could not encode graph PNG" }.use { it.bytes }
            }
        } finally { scene.close() }
    }
