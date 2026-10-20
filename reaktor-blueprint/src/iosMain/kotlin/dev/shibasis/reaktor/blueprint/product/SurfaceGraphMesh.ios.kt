package dev.shibasis.reaktor.blueprint.product

import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaShader
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

private val plain by lazy { Paint() }
private val textured by lazy { Paint() }

internal actual fun DrawScope.drawSurfaceGraphMesh(mesh: SurfaceGraphMesh, shader: Shader?) {
    val canvas = drawContext.canvas.skiaCanvas
    val paint = if (shader == null) plain else textured.apply { this.shader = shader.skiaShader }
    for (b in 0 until mesh.batchCount) {
        val v0 = mesh.batches[b * 4]; val v1 = mesh.batches[b * 4 + 1]; val i0 = mesh.batches[b * 4 + 2]; val i1 = mesh.batches[b * 4 + 3]
        canvas.drawVertices(VertexMode.TRIANGLES, mesh.positions.copyOfRange(v0 * 2, v1 * 2), mesh.colors.copyOfRange(v0, v1),
            if (shader == null) null else mesh.texture.copyOfRange(v0 * 2, v1 * 2), mesh.indices.copyOfRange(i0, i1),
            if (shader == null) BlendMode.DST else BlendMode.MODULATE, paint)
    }
}

internal actual fun surfaceGraphFrameElapsed(): Double = Double.NaN
