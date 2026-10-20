package dev.shibasis.reaktor.blueprint.product

import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal expect fun DrawScope.drawSurfaceGraphMesh(mesh: SurfaceGraphMesh, shader: Shader?)

internal class SurfaceGraphMesh(val textured: Boolean) {
    var positions = FloatArray(8192); private set
    var texture = FloatArray(if (textured) 8192 else 0); private set
    var colors = IntArray(4096); private set
    var indices = ShortArray(12288); private set
    var vertexCount = 0; private set
    var indexCount = 0; private set
    var batches = IntArray(16); private set
    var batchCount = 0; private set
    private var first = 0
    private var firstIndex = 0

    fun clear() { vertexCount = 0; indexCount = 0; batchCount = 0; first = 0; firstIndex = 0 }

    fun reserve(vertices: Int, triangles: Int) {
        if (vertexCount - first + vertices > 65_535) close()
        if ((vertexCount + vertices) * 2 > positions.size) {
            val size = max(positions.size * 2, (vertexCount + vertices) * 2)
            positions = positions.copyOf(size); colors = colors.copyOf(size / 2)
            if (textured) texture = texture.copyOf(size)
        }
        if (indexCount + triangles * 3 > indices.size) indices = indices.copyOf(max(indices.size * 2, indexCount + triangles * 3))
    }

    fun close() {
        if (vertexCount > first) {
            if (batchCount * 4 + 4 > batches.size) batches = batches.copyOf(batches.size * 2)
            batches[batchCount * 4] = first; batches[batchCount * 4 + 1] = vertexCount
            batches[batchCount * 4 + 2] = firstIndex; batches[batchCount * 4 + 3] = indexCount
            batchCount++
        }
        first = vertexCount; firstIndex = indexCount
    }

    fun vertex(x: Float, y: Float, color: Int, u: Float = 0f): Int {
        val i = vertexCount++
        positions[i * 2] = x; positions[i * 2 + 1] = y; colors[i] = color
        if (textured) { texture[i * 2] = u; texture[i * 2 + 1] = 0f }
        return i - first
    }

    fun triangle(a: Int, b: Int, c: Int) {
        indices[indexCount] = a.toShort(); indices[indexCount + 1] = b.toShort(); indices[indexCount + 2] = c.toShort()
        indexCount += 3
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { triangle(a, b, c); triangle(a, c, d) }
}

private fun norm(x: Float, y: Float) = sqrt(x * x + y * y)

internal fun surfaceGraphArgb(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

internal class SurfaceGraphStroker {
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var arc = FloatArray(256)
    private var nx = FloatArray(256)
    private var ny = FloatArray(256)
    private var miter = FloatArray(256)
    private val edgeX = FloatArray(3)
    private val edgeY = FloatArray(3)
    private val corner = IntArray(6)
    private var previous = -1
    var size = 0; private set

    fun clear() { size = 0 }

    fun point(x: Float, y: Float) {
        if (size > 0 && abs(xs[size - 1] - x) < 1e-3f && abs(ys[size - 1] - y) < 1e-3f) return
        if (size == xs.size) {
            val n = size * 2
            xs = xs.copyOf(n); ys = ys.copyOf(n); arc = arc.copyOf(n); nx = nx.copyOf(n); ny = ny.copyOf(n); miter = miter.copyOf(n)
        }
        xs[size] = x; ys[size] = y; size++
    }

    fun quad(cx: Float, cy: Float, x: Float, y: Float, tolerance: Float) {
        val x0 = xs[size - 1]; val y0 = ys[size - 1]
        val n = ceil(sqrt(norm(x0 - 2 * cx + x, y0 - 2 * cy + y) / (4 * tolerance))).toInt().coerceIn(1, 64)
        for (i in 1..n) {
            val t = i.toFloat() / n; val m = 1 - t
            point(m * m * x0 + 2 * m * t * cx + t * t * x, m * m * y0 + 2 * m * t * cy + t * t * y)
        }
    }

    fun cubic(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float, tolerance: Float) {
        val x0 = xs[size - 1]; val y0 = ys[size - 1]
        val bend = max(norm(x0 - 2 * ax + bx, y0 - 2 * ay + by), norm(ax - 2 * bx + x, ay - 2 * by + y))
        val n = ceil(sqrt(.75f * bend / tolerance)).toInt().coerceIn(1, 128)
        for (i in 1..n) {
            val t = i.toFloat() / n; val m = 1 - t
            val a = m * m * m; val b = 3 * m * m * t; val c = 3 * m * t * t; val d = t * t * t
            point(a * x0 + b * ax + c * bx + d * x, a * y0 + b * ay + c * by + d * y)
        }
    }

    fun corners(points: FloatArray, from: Int, to: Int, scale: Float, ox: Float, oy: Float, radius: Float, tolerance: Float) {
        val count = (to - from) / 2
        point(points[from] * scale - ox, points[from + 1] * scale - oy)
        for (i in 1 until count - 1) {
            val at = from + i * 2
            val px = points[at - 2]; val py = points[at - 1]; val cx = points[at]; val cy = points[at + 1]; val qx = points[at + 2]; val qy = points[at + 3]
            val into = norm(cx - px, cy - py); val out = norm(qx - cx, qy - cy)
            val r = min(radius, min(into / 2, out / 2))
            if (r < .5f || into == 0f || out == 0f) point(cx * scale - ox, cy * scale - oy)
            else {
                point((cx - (cx - px) / into * r) * scale - ox, (cy - (cy - py) / into * r) * scale - oy)
                quad(cx * scale - ox, cy * scale - oy, (cx + (qx - cx) / out * r) * scale - ox, (cy + (qy - cy) / out * r) * scale - oy, tolerance)
            }
        }
        point(points[to - 2] * scale - ox, points[to - 1] * scale - oy)
    }

    private fun prepare(): Float {
        arc[0] = 0f
        for (i in 1 until size) arc[i] = arc[i - 1] + norm(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        var ax = 0f; var ay = 0f
        for (i in 0 until size) {
            var bx = 0f; var by = 0f
            if (i < size - 1) {
                val dx = xs[i + 1] - xs[i]; val dy = ys[i + 1] - ys[i]; val l = norm(dx, dy)
                if (l > 0f) { bx = -dy / l; by = dx / l }
            }
            if (i == 0) { nx[i] = bx; ny[i] = by; miter[i] = 1f }
            else if (i == size - 1) { nx[i] = ax; ny[i] = ay; miter[i] = 1f }
            else {
                val sx = ax + bx; val sy = ay + by; val l = norm(sx, sy)
                if (l < 1e-4f) { nx[i] = bx; ny[i] = by; miter[i] = 1f }
                else { nx[i] = sx / l; ny[i] = sy / l; miter[i] = min(1f / max(nx[i] * bx + ny[i] * by, 1e-3f), 4f) }
            }
            ax = bx; ay = by
        }
        return arc[size - 1]
    }

    private fun row(mesh: SurfaceGraphMesh, x: Float, y: Float, nx: Float, ny: Float, scale: Float, t: Float, outer: Float, inner: Float, rgb: Int, alpha: Float) {
        val solid = surfaceGraphArgb(rgb, alpha); val clear = rgb and 0xFFFFFF
        val o = outer * scale; val n = inner * scale
        val a = mesh.vertex(x + nx * o, y + ny * o, clear, t)
        val b = mesh.vertex(x + nx * n, y + ny * n, solid, t)
        val c = mesh.vertex(x - nx * n, y - ny * n, solid, t)
        val d = mesh.vertex(x - nx * o, y - ny * o, clear, t)
        if (previous >= 0) { mesh.quad(previous, previous + 1, b, a); mesh.quad(previous + 1, previous + 2, c, b); mesh.quad(previous + 2, previous + 3, d, c) }
        previous = a
    }

    fun stroke(mesh: SurfaceGraphMesh, halfWidth: Float, fringe: Float, rgb: Int, alpha: Float, round: Boolean = false, dashed: Boolean = false) {
        if (size < 2) return
        val length = prepare()
        if (length <= 0f) return
        val outer = halfWidth + fringe / 2; val inner = max(halfWidth - fringe / 2, 0f)
        val peak = alpha * min(1f, 2 * halfWidth / fringe)
        val steps = if (round) capSteps(outer, fringe) else 0
        mesh.reserve((size + 4) * 4 + 2 * (3 + 2 * steps), (size + 4) * 6 + 6 * steps)
        previous = -1
        val last = size - 1
        val head = arc[1].coerceAtLeast(1e-6f); val tail = (length - arc[last - 1]).coerceAtLeast(1e-6f)
        val hx = (xs[1] - xs[0]) / head; val hy = (ys[1] - ys[0]) / head
        val tx = (xs[last] - xs[last - 1]) / tail; val ty = (ys[last] - ys[last - 1]) / tail
        if (round) {
            cap(mesh, xs[0], ys[0], -hx, -hy, nx[0], ny[0], inner, outer, steps, rgb, peak)
            for (i in 0..last) row(mesh, xs[i], ys[i], nx[i], ny[i], miter[i], arc[i], outer, inner, rgb, peak)
            cap(mesh, xs[last], ys[last], tx, ty, -nx[last], -ny[last], inner, outer, steps, rgb, peak)
            return
        }
        val half = fringe / 2
        fun coverage(t: Float) = peak * (min(if (dashed) fringe else t + half, length + half - t) / fringe).coerceIn(0f, 1f)
        row(mesh, xs[0] - hx * half, ys[0] - hy * half, nx[0], ny[0], 1f, -half, outer, inner, rgb, if (dashed) peak else 0f)
        if (length > fringe) {
            val lead = min(half, arc[1])
            if (lead < arc[1]) row(mesh, xs[0] + hx * lead, ys[0] + hy * lead, nx[0], ny[0], 1f, lead, outer, inner, rgb, coverage(lead))
            for (i in 1 until last) row(mesh, xs[i], ys[i], nx[i], ny[i], miter[i], arc[i], outer, inner, rgb, coverage(arc[i]))
            val trail = min(half, tail)
            if (length - trail > arc[last - 1]) row(mesh, xs[last] - tx * trail, ys[last] - ty * trail, nx[last], ny[last], 1f, length - trail, outer, inner, rgb, coverage(length - trail))
        } else row(mesh, (xs[0] + xs[last]) / 2, (ys[0] + ys[last]) / 2, nx[0], ny[0], 1f, length / 2, outer, inner, rgb, coverage(length / 2))
        row(mesh, xs[last] + tx * half, ys[last] + ty * half, nx[last], ny[last], 1f, length + half, outer, inner, rgb, 0f)
    }

    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, tolerance: Float) {
        size = 0
        val rr = min(radius, min(r - l, b - t) / 2).coerceAtLeast(0f)
        val n = if (rr <= tolerance) 0 else ceil(PI / 4 / acos(1.0 - tolerance / rr)).toInt().coerceIn(1, 16)
        arc(r - rr, t + rr, rr, -PI / 2, n); arc(r - rr, b - rr, rr, 0.0, n); arc(l + rr, b - rr, rr, PI / 2, n); arc(l + rr, t + rr, rr, PI, n)
        if (size > 2 && abs(xs[size - 1] - xs[0]) < 1e-3f && abs(ys[size - 1] - ys[0]) < 1e-3f) size--
    }

    private fun arc(cx: Float, cy: Float, radius: Float, start: Double, steps: Int) {
        if (steps == 0) { point(cx, cy); return }
        for (k in 0..steps) { val a = start + PI / 2 * k / steps; point(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat()) }
    }

    private fun loop() {
        for (i in 0 until size) {
            val p = if (i == 0) size - 1 else i - 1; val q = if (i == size - 1) 0 else i + 1
            val ax = xs[i] - xs[p]; val ay = ys[i] - ys[p]; val la = norm(ax, ay)
            val bx = xs[q] - xs[i]; val by = ys[q] - ys[i]; val lb = norm(bx, by)
            val px = if (la > 0f) -ay / la else 0f; val py = if (la > 0f) ax / la else 0f
            val qx = if (lb > 0f) -by / lb else 0f; val qy = if (lb > 0f) bx / lb else 0f
            val sx = px + qx; val sy = py + qy; val l = norm(sx, sy)
            if (l < 1e-4f) { nx[i] = qx; ny[i] = qy; miter[i] = 1f }
            else { nx[i] = sx / l; ny[i] = sy / l; miter[i] = min(1f / max(nx[i] * qx + ny[i] * qy, 1e-3f), 4f) }
        }
    }

    fun fill(mesh: SurfaceGraphMesh, fringe: Float, argb: Int, extent: Float) {
        if (size < 3) return
        loop()
        val inset = min(fringe / 2, extent / 2); val outset = fringe / 2
        val solid = if (extent >= fringe) argb else surfaceGraphArgb(argb, (argb ushr 24) / 255f * extent / fringe)
        val clear = argb and 0xFFFFFF
        mesh.reserve(size * 2 + 1, size * 3)
        var cx = 0f; var cy = 0f
        for (i in 0 until size) { cx += xs[i]; cy += ys[i] }
        val centre = mesh.vertex(cx / size, cy / size, solid)
        for (i in 0 until size) {
            val m = miter[i]
            mesh.vertex(xs[i] + nx[i] * inset * m, ys[i] + ny[i] * inset * m, solid)
            mesh.vertex(xs[i] - nx[i] * outset * m, ys[i] - ny[i] * outset * m, clear)
        }
        for (i in 0 until size) {
            val a = centre + 1 + i * 2; val b = centre + 1 + (if (i == size - 1) 0 else i + 1) * 2
            mesh.triangle(centre, a, b); mesh.quad(a, a + 1, b + 1, b)
        }
    }

    fun outline(mesh: SurfaceGraphMesh, halfWidth: Float, fringe: Float, rgb: Int, alpha: Float) {
        if (size < 3) return
        loop()
        val outer = halfWidth + fringe / 2; val inner = max(halfWidth - fringe / 2, 0f)
        mesh.reserve(size * 4, size * 6)
        previous = -1
        var first = -1
        for (i in 0 until size) {
            row(mesh, xs[i], ys[i], nx[i], ny[i], miter[i], 0f, outer, inner, rgb, alpha * min(1f, 2 * halfWidth / fringe))
            if (first < 0) first = previous
        }
        mesh.quad(previous, previous + 1, first + 1, first); mesh.quad(previous + 1, previous + 2, first + 2, first + 1); mesh.quad(previous + 2, previous + 3, first + 3, first + 2)
    }

    private fun capSteps(radius: Float, fringe: Float) = ceil(radius / fringe / 2).toInt().coerceIn(4, 32)

    private fun cap(mesh: SurfaceGraphMesh, x: Float, y: Float, dx: Float, dy: Float, sx: Float, sy: Float, inner: Float, outer: Float, steps: Int, rgb: Int, peak: Float) {
        val solid = surfaceGraphArgb(rgb, peak); val clear = rgb and 0xFFFFFF
        val centre = mesh.vertex(x, y, solid)
        var lastInner = -1; var lastOuter = -1
        for (k in 0..steps) {
            val angle = (PI * k / steps).toFloat()
            val c = cos(angle); val s = sin(angle)
            val ux = sx * c + dx * s; val uy = sy * c + dy * s
            val i = mesh.vertex(x + ux * inner, y + uy * inner, solid)
            val o = mesh.vertex(x + ux * outer, y + uy * outer, clear)
            if (lastInner >= 0) { mesh.triangle(centre, lastInner, i); mesh.quad(lastInner, lastOuter, o, i) }
            lastInner = i; lastOuter = o
        }
    }

    fun triangle(mesh: SurfaceGraphMesh, x: FloatArray, y: FloatArray, fringe: Float, rgb: Int, alpha: Float) {
        val cx = (x[0] + x[1] + x[2]) / 3; val cy = (y[0] + y[1] + y[2]) / 3
        for (k in 0..2) {
            val j = (k + 1) % 3; val dx = x[j] - x[k]; val dy = y[j] - y[k]; val l = norm(dx, dy)
            if (l == 0f) return
            var px = -dy / l; var py = dx / l
            if (px * ((x[k] + x[j]) / 2 - cx) + py * ((y[k] + y[j]) / 2 - cy) < 0) { px = -px; py = -py }
            edgeX[k] = px; edgeY[k] = py
        }
        mesh.reserve(6, 7)
        val solid = surfaceGraphArgb(rgb, alpha); val clear = rgb and 0xFFFFFF
        for (k in 0..2) {
            val p = (k + 2) % 3
            val sx = edgeX[p] + edgeX[k]; val sy = edgeY[p] + edgeY[k]; val d = 1 + edgeX[p] * edgeX[k] + edgeY[p] * edgeY[k]
            val ox = if (d < 1e-3f) 0f else sx / d * fringe / 2; val oy = if (d < 1e-3f) 0f else sy / d * fringe / 2
            corner[k] = mesh.vertex(x[k] - ox, y[k] - oy, solid)
            corner[k + 3] = mesh.vertex(x[k] + ox, y[k] + oy, clear)
        }
        mesh.triangle(corner[0], corner[1], corner[2])
        for (k in 0..2) { val j = (k + 1) % 3; mesh.quad(corner[k], corner[k + 3], corner[j + 3], corner[j]) }
    }
}
