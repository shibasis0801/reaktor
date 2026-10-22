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
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

private val plain by lazy { Paint() }
private val textured by lazy { Paint() }

internal actual fun DrawScope.drawSurfaceGraphMesh(mesh: SurfaceGraphMesh, shader: Shader?, from: Int, to: Int) {
    val canvas = drawContext.canvas.skiaCanvas
    val paint = if (shader == null) plain else textured.apply { this.shader = shader.skiaShader }
    for (b in from until to) {
        val v0 = mesh.batches[b * 4]; val v1 = mesh.batches[b * 4 + 1]; val i0 = mesh.batches[b * 4 + 2]; val i1 = mesh.batches[b * 4 + 3]
        canvas.drawVertices(VertexMode.TRIANGLES, mesh.positions.copyOfRange(v0 * 2, v1 * 2), mesh.colors.copyOfRange(v0, v1),
            if (shader == null) null else mesh.texture.copyOfRange(v0 * 2, v1 * 2), mesh.indices.copyOfRange(i0, i1),
            if (shader == null) BlendMode.DST else BlendMode.MODULATE, paint)
    }
}

internal actual fun surfaceGraphFrameElapsed(): Double = Double.NaN
internal actual val surfaceGraphMeshGrid = true

@OptIn(InternalComposeUiApi::class)
@Composable
internal actual fun surfaceGraphIdle(): (((Long) -> Boolean) -> Unit)? {
    val scheduler = LocalPlatformPrefetchScheduler.current
    return remember(scheduler) { { work -> scheduler.scheduleHighPriorityPrefetch(object : PlatformPrefetchRequest { override fun PlatformPrefetchRequestScope.execute() = work(availableTimeNanos()) }) } }
}
