package dev.shibasis.reaktor.blueprint.product

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

private val paint = Paint()

internal actual fun DrawScope.drawSurfaceGraphMesh(mesh: SurfaceGraphMesh, shader: Shader?) {
    val canvas = drawContext.canvas.nativeCanvas
    paint.shader = shader
    for (b in 0 until mesh.batchCount) {
        val v0 = mesh.batches[b * 4]; val v1 = mesh.batches[b * 4 + 1]; val i0 = mesh.batches[b * 4 + 2]; val i1 = mesh.batches[b * 4 + 3]
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, (v1 - v0) * 2, mesh.positions, v0 * 2, if (shader == null) null else mesh.texture, v0 * 2,
            mesh.colors, v0, mesh.indices, i0, i1 - i0, paint)
    }
}

internal actual fun surfaceGraphFrameElapsed(): Double = Double.NaN
