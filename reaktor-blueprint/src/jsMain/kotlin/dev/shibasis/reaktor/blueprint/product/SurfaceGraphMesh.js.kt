package dev.shibasis.reaktor.blueprint.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaShader
import androidx.compose.ui.platform.LocalPlatformPrefetchScheduler
import androidx.compose.ui.platform.PlatformPrefetchRequest
import androidx.compose.ui.platform.PlatformPrefetchRequestScope
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import org.jetbrains.skiko.InternalSkikoApi
import org.jetbrains.skiko.wasm.awaitSkiko
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int16Array
import org.khronos.webgl.Int32Array
import kotlin.math.max

private val plain by lazy { Paint() }
private val textured by lazy { Paint() }

private fun FloatArray.view(from: Int, to: Int): FloatArray = asDynamic().subarray(from, to).unsafeCast<FloatArray>()
private fun IntArray.view(from: Int, to: Int): IntArray = asDynamic().subarray(from, to).unsafeCast<IntArray>()
private fun ShortArray.view(from: Int, to: Int): ShortArray = asDynamic().subarray(from, to).unsafeCast<ShortArray>()

@OptIn(InternalSkikoApi::class)
private object Heap {
    private var exports: dynamic = null
    private val pointers by lazy { "$plain".endsWith("(_ptr=0x${plain.hashCode().toString(16)})") }
    private var block = 0
    private var capacity = 0

    init { awaitSkiko.asDynamic().then { module: dynamic -> exports = module.wasmExports } }

    fun draw(canvas: Canvas, paint: Paint, mesh: SurfaceGraphMesh, coords: Boolean, blend: BlendMode, v0: Int, v1: Int, i0: Int, i1: Int): Boolean {
        val exports = exports
        if (exports == null || !pointers) return false
        val count = v1 - v0; val n = i1 - i0
        val tex = count * 12; val idx = tex + if (coords) count * 8 else 0
        if (idx + n * 2 > capacity) {
            if (block != 0) exports.free(block)
            capacity = max(idx + n * 2, capacity * 2); block = exports.malloc(capacity).unsafeCast<Int>()
            if (block == 0) { capacity = 0; return false }
        }
        val heap = exports.memory.buffer.unsafeCast<ArrayBuffer>()
        Float32Array(heap, block, count * 2).set(mesh.positions.unsafeCast<Float32Array>().subarray(v0 * 2, v1 * 2))
        Int32Array(heap, block + count * 8, count).set(mesh.colors.unsafeCast<Int32Array>().subarray(v0, v1))
        if (coords) Float32Array(heap, block + tex, count * 2).set(mesh.texture.unsafeCast<Float32Array>().subarray(v0 * 2, v1 * 2))
        Int16Array(heap, block + idx, n).set(mesh.indices.unsafeCast<Int16Array>().subarray(i0, i1))
        exports.org_jetbrains_skia_Canvas__1nDrawVertices(canvas.hashCode(), VertexMode.TRIANGLES.ordinal, count, block, block + count * 8,
            if (coords) block + tex else 0, n, block + idx, blend.ordinal, paint.hashCode())
        return true
    }
}

internal actual fun DrawScope.drawSurfaceGraphMesh(mesh: SurfaceGraphMesh, shader: Shader?, from: Int, to: Int) {
    val canvas = drawContext.canvas.skiaCanvas
    val paint = if (shader == null) plain else textured.apply { this.shader = shader.skiaShader }
    val blend = if (shader == null) BlendMode.DST else BlendMode.MODULATE
    for (b in from until to) {
        val v0 = mesh.batches[b * 4]; val v1 = mesh.batches[b * 4 + 1]; val i0 = mesh.batches[b * 4 + 2]; val i1 = mesh.batches[b * 4 + 3]
        if (!Heap.draw(canvas, paint, mesh, shader != null, blend, v0, v1, i0, i1))
            canvas.drawVertices(VertexMode.TRIANGLES, mesh.positions.view(v0 * 2, v1 * 2), mesh.colors.view(v0, v1),
                if (shader == null) null else mesh.texture.view(v0 * 2, v1 * 2), mesh.indices.view(i0, i1), blend, paint)
    }
}

internal actual fun surfaceGraphFrameElapsed(): Double = js("typeof document !== 'undefined' && document.timeline && document.timeline.currentTime != null ? performance.now() - document.timeline.currentTime : NaN").unsafeCast<Double>()
internal actual val surfaceGraphMeshGrid = true

@OptIn(InternalComposeUiApi::class)
@Composable
internal actual fun surfaceGraphIdle(): (((Long) -> Boolean) -> Unit)? {
    val scheduler = LocalPlatformPrefetchScheduler.current
    return remember(scheduler) { { work -> scheduler.scheduleHighPriorityPrefetch(object : PlatformPrefetchRequest { override fun PlatformPrefetchRequestScope.execute() = work(availableTimeNanos()) }) } }
}
