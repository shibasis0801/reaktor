package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.flatbuffers.StringAccess
import dev.shibasis.reaktor.flexbuffer.flatbuffers.UnsafeOps
import dev.shibasis.reaktor.flexbuffer.flatbuffers.Utf8
import dev.shibasis.reaktor.flexbuffer.flatbuffers.fastEncodedLength
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ld32
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ld64
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ldF32
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ldF64
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ld16
import dev.shibasis.reaktor.flexbuffer.flatbuffers.st16
import dev.shibasis.reaktor.flexbuffer.flatbuffers.st32
import dev.shibasis.reaktor.flexbuffer.flatbuffers.st64
import dev.shibasis.reaktor.flexbuffer.flatbuffers.stF32
import dev.shibasis.reaktor.flexbuffer.flatbuffers.stF64
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

/**
 * Physical-device A/B for the Android primitive bulk-transfer policy.
 *
 * androidMain PrimitiveArrayCopy currently returns false for every bulk kernel,
 * keeping ART on the width-specialized scalar ld/st path. The performance audit
 * (PERFORMANCE_AUDIT.md, roadmap item 8) requires a physical-ART measurement before
 * enabling bulk kernels. This benchmark compares, on-device:
 *
 *   scalar     — the production fallback loop over the module's ld/st intrinsics
 *   bytebuffer — java.nio heap ByteBuffer.order(LITTLE_ENDIAN) typed-view bulk get/put
 *   unsafe     — sun.misc.Unsafe object-overload copyMemory, if this ART exposes it
 *
 * Each cell verifies byte/array equality against the scalar arm before timing.
 * Output lines are prefixed ANDROID_BULK_AB| for machine collection.
 */
class PrimitiveBulkAbBenchmark {

    private val sizes = intArrayOf(256, 4096)
    private val warmup = 2_000
    private val measureIters = 20_000

    private var sink: Any? = null

    private val unsafe: sun.misc.Unsafe? = try {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null) as sun.misc.Unsafe
    } catch (t: Throwable) {
        null
    }

    // Object-overload copyMemory(Object, long, Object, long, long) — probed reflectively
    // because the SDK stub does not guarantee it. If present we invoke through a cached
    // Method; a production integration would link it directly, so we also report the
    // reflective overhead separately at size 4096 via a per-element amortization note.
    private val copyMemoryObj = try {
        sun.misc.Unsafe::class.java.getMethod(
            "copyMemory",
            Any::class.java, Long::class.javaPrimitiveType,
            Any::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        )
    } catch (t: Throwable) {
        null
    }

    private val byteBase: Long = unsafe?.arrayBaseOffset(ByteArray::class.java)?.toLong() ?: -1L
    private val intBase: Long = unsafe?.arrayBaseOffset(IntArray::class.java)?.toLong() ?: -1L
    private val shortBase: Long = unsafe?.arrayBaseOffset(ShortArray::class.java)?.toLong() ?: -1L
    private val longBase: Long = unsafe?.arrayBaseOffset(LongArray::class.java)?.toLong() ?: -1L
    private val floatBase: Long = unsafe?.arrayBaseOffset(FloatArray::class.java)?.toLong() ?: -1L
    private val doubleBase: Long = unsafe?.arrayBaseOffset(DoubleArray::class.java)?.toLong() ?: -1L

    private inline fun bench(crossinline op: () -> Any?): Double {
        repeat(warmup) { sink = op() }
        var minNs = Long.MAX_VALUE
        repeat(3) {
            val ns = measureNanoTime { repeat(measureIters) { sink = op() } }
            if (ns < minNs) minNs = ns
        }
        return minNs.toDouble() / measureIters
    }

    private fun report(width: String, dir: String, size: Int, arm: String, nsPerOp: Double) {
        val perElem = nsPerOp / size
        println(
            "ANDROID_BULK_AB|width=$width|dir=$dir|size=$size|arm=$arm|" +
                "nsPerOp=${"%.1f".format(nsPerOp)}|nsPerElem=${"%.4f".format(perElem)}"
        )
    }

    @Test
    fun primitiveBulkAb() {
        println("ANDROID_BULK_AB|meta|unsafe=${unsafe != null}|copyMemoryObj=${copyMemoryObj != null}|api=${android.os.Build.VERSION.SDK_INT}|device=${android.os.Build.MODEL}")

        for (n in sizes) {
            intCase(n)
            shortCase(n)
            longCase(n)
            floatCase(n)
            doubleCase(n)
        }
    }

    /**
     * ART string materialization A/B. MicroBench measured ~293 ns for a 1-byte
     * decodeToString on this device class — an order of magnitude above the JVM
     * floor. StringFactory's ISO-8859-1 constructor is a compact-string blit and
     * yields identical results for pure-ASCII payloads (keys, ids, enum names).
     */
    @Test
    fun asciiStringDecodeAb() {
        for (len in intArrayOf(1, 5, 24, 64, 256)) {
            val bytes = ByteArray(len + 8) { ('a' + (it % 26)).code.toByte() }
            val start = 4
            val end = start + len
            val expect = bytes.decodeToString(start, end)

            val utf8Arm = { bytes.decodeToString(start, end) }
            val latin1Arm = { String(bytes, start, len, Charsets.ISO_8859_1) }
            check(latin1Arm() == expect)

            val utf8Ns = bench { utf8Arm() }
            val latin1Ns = bench { latin1Arm() }
            println(
                "ANDROID_STRING_AB|len=$len|utf8DecodeNs=${"%.1f".format(utf8Ns)}|" +
                    "latin1CtorNs=${"%.1f".format(latin1Ns)}|speedup=${"%.2f".format(utf8Ns / latin1Ns)}"
            )
        }
    }

    /**
     * ART string ENCODE A/B. Android currently routes every string through the
     * portable two-pass Utf8.encodeUtf8Array; JVM uses a Latin-1 String.value
     * arraycopy. Answers (a) does String.value/coder reflection resolve on this
     * ART, and (b) what a compact-Latin-1 bulk copy would buy over the portable
     * loop on real hardware.
     */
    @Test
    fun asciiStringEncodeAb() {
        println(
            "ANDROID_STRING_ENC_AB|meta|stringAccessOn=${StringAccess.ON}|" +
                "unsafeOn=${UnsafeOps.ON}"
        )
        for (len in intArrayOf(5, 24, 64, 256)) {
            val s = buildString { repeat(len) { append('a' + (it % 26)) } }
            val out = ByteArray(len + 16)

            val portableArm = { Utf8.encodeUtf8Array(s, out, 4, out.size - 4) }
            // encodeUtf8Array returns the end position, not the encoded length.
            val encodedLen: Int = portableArm() - 4
            val expectBytes = out.copyOf()

            val lengthScanArm = { fastEncodedLength(s) }
            check(lengthScanArm() == encodedLen)

            val portableNs = bench { portableArm() }
            val lengthNs = bench { lengthScanArm() }

            var reflectNs = -1.0
            if (StringAccess.ON) {
                val u = UnsafeOps.U!!
                val reflectArm = arm@{
                    if (u.getByte(s, StringAccess.CODER_OFFSET).toInt() == 0) {
                        val value = u.getObject(s, StringAccess.VALUE_OFFSET) as ByteArray
                        System.arraycopy(value, 0, out, 4, value.size)
                        return@arm value.size
                    }
                    Utf8.encodeUtf8Array(s, out, 4, out.size - 4)
                }
                java.util.Arrays.fill(out, 0)
                check(reflectArm() == encodedLen)
                check(out.contentEquals(expectBytes))
                reflectNs = bench { reflectArm() }
            }
            println(
                "ANDROID_STRING_ENC_AB|len=$len|portableEncodeNs=${"%.1f".format(portableNs)}|" +
                    "lengthScanNs=${"%.1f".format(lengthNs)}|latin1CopyNs=${"%.1f".format(reflectNs)}"
            )
        }
    }

    // ── Int ──
    private fun intCase(n: Int) {
        val src = IntArray(n) { it * 2654435761.toInt() }
        val bytes = ByteArray(n * 4)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // encode arms
        val scalarEnc = { for (i in 0 until n) bytes.st32(i * 4, src[i]); bytes }
        scalarEnc()
        val expected = bytes.copyOf()
        val bbEnc = { bb.clear(); bb.asIntBuffer().put(src, 0, n); bytes }
        bbEnc(); assertArrayEquals(expected, bytes)
        report("int", "enc", n, "scalar", bench { scalarEnc() })
        report("int", "enc", n, "bytebuffer", bench { bbEnc() })
        if (copyMemoryObj != null) {
            val uEnc = { copyMemoryObj.invoke(unsafe, src, intBase, bytes, byteBase, (n * 4).toLong()); bytes }
            uEnc(); assertArrayEquals(expected, bytes)
            report("int", "enc", n, "unsafe", bench { uEnc() })
        }

        // decode arms
        val dst = IntArray(n)
        val scalarDec = { for (i in 0 until n) dst[i] = bytes.ld32(i * 4); dst }
        scalarDec(); assertArrayEquals(src, dst)
        val bbDec = { bb.clear(); bb.asIntBuffer().get(dst, 0, n); dst }
        java.util.Arrays.fill(dst, 0); bbDec(); assertArrayEquals(src, dst)
        report("int", "dec", n, "scalar", bench { scalarDec() })
        report("int", "dec", n, "bytebuffer", bench { bbDec() })
        if (copyMemoryObj != null) {
            val uDec = { copyMemoryObj.invoke(unsafe, bytes, byteBase, dst, intBase, (n * 4).toLong()); dst }
            java.util.Arrays.fill(dst, 0); uDec(); assertArrayEquals(src, dst)
            report("int", "dec", n, "unsafe", bench { uDec() })
        }
    }

    // ── Short ──
    private fun shortCase(n: Int) {
        val src = ShortArray(n) { (it * 31).toShort() }
        val bytes = ByteArray(n * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val scalarEnc = { for (i in 0 until n) bytes.st16(i * 2, src[i].toInt()); bytes }
        scalarEnc()
        val expected = bytes.copyOf()
        val bbEnc = { bb.clear(); bb.asShortBuffer().put(src, 0, n); bytes }
        bbEnc(); assertArrayEquals(expected, bytes)
        report("short", "enc", n, "scalar", bench { scalarEnc() })
        report("short", "enc", n, "bytebuffer", bench { bbEnc() })
        if (copyMemoryObj != null) {
            val uEnc = { copyMemoryObj.invoke(unsafe, src, shortBase, bytes, byteBase, (n * 2).toLong()); bytes }
            uEnc(); assertArrayEquals(expected, bytes)
            report("short", "enc", n, "unsafe", bench { uEnc() })
        }

        val dst = ShortArray(n)
        val scalarDec = { for (i in 0 until n) dst[i] = bytes.ld16(i * 2).toShort(); dst }
        scalarDec(); assertArrayEquals(src, dst)
        val bbDec = { bb.clear(); bb.asShortBuffer().get(dst, 0, n); dst }
        java.util.Arrays.fill(dst, 0.toShort()); bbDec(); assertArrayEquals(src, dst)
        report("short", "dec", n, "scalar", bench { scalarDec() })
        report("short", "dec", n, "bytebuffer", bench { bbDec() })
        if (copyMemoryObj != null) {
            val uDec = { copyMemoryObj.invoke(unsafe, bytes, byteBase, dst, shortBase, (n * 2).toLong()); dst }
            java.util.Arrays.fill(dst, 0.toShort()); uDec(); assertArrayEquals(src, dst)
            report("short", "dec", n, "unsafe", bench { uDec() })
        }
    }

    // ── Long ──
    private fun longCase(n: Int) {
        val src = LongArray(n) { it * -7046029254386353131L }
        val bytes = ByteArray(n * 8)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val scalarEnc = { for (i in 0 until n) bytes.st64(i * 8, src[i]); bytes }
        scalarEnc()
        val expected = bytes.copyOf()
        val bbEnc = { bb.clear(); bb.asLongBuffer().put(src, 0, n); bytes }
        bbEnc(); assertArrayEquals(expected, bytes)
        report("long", "enc", n, "scalar", bench { scalarEnc() })
        report("long", "enc", n, "bytebuffer", bench { bbEnc() })
        if (copyMemoryObj != null) {
            val uEnc = { copyMemoryObj.invoke(unsafe, src, longBase, bytes, byteBase, (n * 8).toLong()); bytes }
            uEnc(); assertArrayEquals(expected, bytes)
            report("long", "enc", n, "unsafe", bench { uEnc() })
        }

        val dst = LongArray(n)
        val scalarDec = { for (i in 0 until n) dst[i] = bytes.ld64(i * 8); dst }
        scalarDec(); assertArrayEquals(src, dst)
        val bbDec = { bb.clear(); bb.asLongBuffer().get(dst, 0, n); dst }
        java.util.Arrays.fill(dst, 0L); bbDec(); assertArrayEquals(src, dst)
        report("long", "dec", n, "scalar", bench { scalarDec() })
        report("long", "dec", n, "bytebuffer", bench { bbDec() })
        if (copyMemoryObj != null) {
            val uDec = { copyMemoryObj.invoke(unsafe, bytes, byteBase, dst, longBase, (n * 8).toLong()); dst }
            java.util.Arrays.fill(dst, 0L); uDec(); assertArrayEquals(src, dst)
            report("long", "dec", n, "unsafe", bench { uDec() })
        }
    }

    // ── Float ──
    private fun floatCase(n: Int) {
        val src = FloatArray(n) { it * 0.7182818f }
        val bytes = ByteArray(n * 4)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val scalarEnc = { for (i in 0 until n) bytes.stF32(i * 4, src[i]); bytes }
        scalarEnc()
        val expected = bytes.copyOf()
        val bbEnc = { bb.clear(); bb.asFloatBuffer().put(src, 0, n); bytes }
        bbEnc(); assertArrayEquals(expected, bytes)
        report("float", "enc", n, "scalar", bench { scalarEnc() })
        report("float", "enc", n, "bytebuffer", bench { bbEnc() })
        if (copyMemoryObj != null) {
            val uEnc = { copyMemoryObj.invoke(unsafe, src, floatBase, bytes, byteBase, (n * 4).toLong()); bytes }
            uEnc(); assertArrayEquals(expected, bytes)
            report("float", "enc", n, "unsafe", bench { uEnc() })
        }

        val dst = FloatArray(n)
        val scalarDec = { for (i in 0 until n) dst[i] = bytes.ldF32(i * 4); dst }
        scalarDec(); assertArrayEquals(src, dst, 0f)
        val bbDec = { bb.clear(); bb.asFloatBuffer().get(dst, 0, n); dst }
        java.util.Arrays.fill(dst, 0f); bbDec(); assertArrayEquals(src, dst, 0f)
        report("float", "dec", n, "scalar", bench { scalarDec() })
        report("float", "dec", n, "bytebuffer", bench { bbDec() })
        if (copyMemoryObj != null) {
            val uDec = { copyMemoryObj.invoke(unsafe, bytes, byteBase, dst, floatBase, (n * 4).toLong()); dst }
            java.util.Arrays.fill(dst, 0f); uDec(); assertArrayEquals(src, dst, 0f)
            report("float", "dec", n, "unsafe", bench { uDec() })
        }
    }

    // ── Double ──
    private fun doubleCase(n: Int) {
        val src = DoubleArray(n) { it * 3.141592653589793 }
        val bytes = ByteArray(n * 8)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val scalarEnc = { for (i in 0 until n) bytes.stF64(i * 8, src[i]); bytes }
        scalarEnc()
        val expected = bytes.copyOf()
        val bbEnc = { bb.clear(); bb.asDoubleBuffer().put(src, 0, n); bytes }
        bbEnc(); assertArrayEquals(expected, bytes)
        report("double", "enc", n, "scalar", bench { scalarEnc() })
        report("double", "enc", n, "bytebuffer", bench { bbEnc() })
        if (copyMemoryObj != null) {
            val uEnc = { copyMemoryObj.invoke(unsafe, src, doubleBase, bytes, byteBase, (n * 8).toLong()); bytes }
            uEnc(); assertArrayEquals(expected, bytes)
            report("double", "enc", n, "unsafe", bench { uEnc() })
        }

        val dst = DoubleArray(n)
        val scalarDec = { for (i in 0 until n) dst[i] = bytes.ldF64(i * 8); dst }
        scalarDec(); assertArrayEquals(src, dst, 0.0)
        val bbDec = { bb.clear(); bb.asDoubleBuffer().get(dst, 0, n); dst }
        java.util.Arrays.fill(dst, 0.0); bbDec(); assertArrayEquals(src, dst, 0.0)
        report("double", "dec", n, "scalar", bench { scalarDec() })
        report("double", "dec", n, "bytebuffer", bench { bbDec() })
        if (copyMemoryObj != null) {
            val uDec = { copyMemoryObj.invoke(unsafe, bytes, byteBase, dst, doubleBase, (n * 8).toLong()); dst }
            java.util.Arrays.fill(dst, 0.0); uDec(); assertArrayEquals(src, dst, 0.0)
            report("double", "dec", n, "unsafe", bench { uDec() })
        }
    }
}
