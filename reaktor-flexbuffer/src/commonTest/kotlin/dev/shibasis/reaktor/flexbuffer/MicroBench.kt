@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ArrayReadBuffer
import dev.shibasis.reaktor.flexbuffer.flatbuffers.ByteWidth
import dev.shibasis.reaktor.flexbuffer.flatbuffers.getRoot
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Per-operation micro-benchmark for diagnosing per-platform bottlenecks.
 * Output goes to System.out — captured in test reports for each target.
 *
 * Run on iOS:  ./gradlew :reaktor-flexbuffer:iosSimulatorArm64Test --tests "*.MicroBench"
 * Run on JVM:  ./gradlew :reaktor-flexbuffer:jvmTest --tests "*.MicroBench"
 *
 * Each operation is measured by running 100k iterations and dividing.
 * Reports nanoseconds per op so cross-platform comparison is direct.
 */
class MicroBench {

    private val iters = 100_000
    private val warmup = 5_000

    private inline fun ns(crossinline op: () -> Any?): Long {
        repeat(warmup) { op() }
        var min = Long.MAX_VALUE
        repeat(3) {
            val elapsed = measureTime { repeat(iters) { op() } }
            val ns = elapsed.inWholeNanoseconds / iters
            if (ns < min) min = ns
        }
        return min
    }

    @Test
    fun microBench() {
        ReaktorFlexbufferCoders.register()

        val data = BenchmarkData.userProfile()
        val bytes = FlexBuffers.encode(data)
        val bigData = BenchmarkData.apiResponse()
        val bigBytes = FlexBuffers.encode(bigData)

        println("=== MICRO-BENCH (ns/op, min of 3 runs) ===")

        // Buffer-level operations
        val readBufferAllocNs = ns { ArrayReadBuffer(bytes) }
        println("ArrayReadBuffer(bytes)        : $readBufferAllocNs ns")

        val getRootNs = ns {
            val buf = ArrayReadBuffer(bytes)
            getRoot(buf)
        }
        println("getRoot(buffer)               : $getRootNs ns")

        val rootRefToMapNs = ns {
            val buf = ArrayReadBuffer(bytes)
            val root = getRoot(buf)
            root.toMap()
        }
        println("getRoot().toMap()             : $rootRefToMapNs ns")

        // Map field access (allocation-free)
        val map = getRoot(ArrayReadBuffer(bytes)).toMap()
        val mapSizeNs = ns { map.size }
        println("map.size                      : $mapSizeNs ns")

        val mapGetIntNs = ns { map.getInt(8) }  // some integer field index
        println("map.getInt(i)                 : $mapGetIntNs ns")

        val mapGetStringNs = ns { map.getString(1) }  // some string field index
        println("map.getString(i)              : $mapGetStringNs ns")

        // Break down getString:
        val rawBytes = bytes
        val rb = ArrayReadBuffer(rawBytes)
        // 1) Just decodeToString of the bytes
        val helloLen = 5
        val helloStart = 0  // wrong start but we just want timing
        val decodeOnlyNs = ns { rb.getString(helloStart, helloLen) }
        println("buffer.getString(0, 5)        : $decodeOnlyNs ns")

        // 2) Just buffer.getUByte (single buffer read)
        val singleByteReadNs = ns { rb.getUByte(0) }
        println("buffer.getUByte(0)            : $singleByteReadNs ns")

        // (skip indirect/readULong — they're internal)

        // 5) byte[1].decodeToString — just to compare raw decode
        val oneByte = byteArrayOf(72.toByte())
        val oneByteDecode = ns { oneByte.decodeToString() }
        println("byte[1].decodeToString()      : $oneByteDecode ns")

        // 6) Just the indices/positions in getString (without buffer reads)
        val mapEnd = 0  // can't access internal, but approximate
        val mapBw = 1
        val mapSize = 14
        val idx = 1
        val pureMathNs = ns {
            val packedPos = mapEnd + mapSize * mapBw + idx
            val objEnd = mapEnd + idx * mapBw
            packedPos + objEnd  // prevent DCE
        }
        println("just int math for getString   : $pureMathNs ns")

        // 7) getStringByteLength — does steps 1-5 without final string decode
        val sblNs = ns { map.getStringByteLength(1) }
        println("map.getStringByteLength(i)    : $sblNs ns")

        // 8) Two virtual calls: getInt followed by getString
        val twoVCallNs = ns { map.getInt(8) + map.getString(1).length }
        println("getInt+getString combined     : $twoVCallNs ns")

        // 9) Try a few string indices, skipping non-string fields safely
        val s1 = map.getString(1)
        println("string at index 1: \"${s1.take(30)}\" (length=${s1.length})")
        val s2 = map.getString(2)
        println("string at index 2: \"${s2.take(30)}\" (length=${s2.length})")

        // 10) Compare short vs long string decode times
        val gsLen2Ns = ns { map.getString(1) }
        println("getString(1) [len ${s1.length}]      : $gsLen2Ns ns")

        // 11) Vector read perf (TimeSeries hot loop)
        val tsBytes = FlexBuffers.encode(BenchmarkData.timeSeriesChunk())
        val tsMap = getRoot(ArrayReadBuffer(tsBytes)).toMap()
        val timestampsVec = tsMap.getVector(7)  // timestamps field
        val valuesVec = tsMap.getVector(8)      // values field
        println("timestamps vec size: ${timestampsVec.size}, values vec size: ${valuesVec.size}")

        val readLongNs = ns { timestampsVec.readLong(0) }
        println("vec.readLong(0)               : $readLongNs ns")

        val readDoubleNs = ns { valuesVec.readDouble(0) }
        println("vec.readDouble(0)             : $readDoubleNs ns")

        val toLongArrayNs = ns { timestampsVec.toLongArray() }
        println("vec.toLongArray() [256 longs] : $toLongArrayNs ns")

        val toDoubleArrayNs = ns { valuesVec.toDoubleArray() }
        println("vec.toDoubleArray() [256 dbls]: $toDoubleArrayNs ns")

        // Cost of boxing 256 longs into ArrayList (what generated code currently does)
        val boxedListNs = ns {
            ArrayList<Long>(timestampsVec.size).also { list ->
                for (i in 0 until timestampsVec.size) list.add(timestampsVec.readLong(i))
            }
        }
        println("ArrayList<Long>(256, boxed)   : $boxedListNs ns")

        val boxedListDoubleNs = ns {
            ArrayList<Double>(valuesVec.size).also { list ->
                for (i in 0 until valuesVec.size) list.add(valuesVec.readDouble(i))
            }
        }
        println("ArrayList<Double>(256, boxed) : $boxedListDoubleNs ns")

        // Cost of just allocating 256 boxed Longs
        val boxLongOnly = ns {
            val list = ArrayList<Long>(256)
            for (i in 0 until 256) list.add(i.toLong())
            list
        }
        println("ArrayList<Long>(256) box only : $boxLongOnly ns")

        // Whole-decode timings to anchor
        val fullDecodeUpNs = ns { FlexBuffers.decode<BenchUserProfile>(bytes) }
        println("FlexBuffers.decode<UserProfile>(): $fullDecodeUpNs ns  (= ${fullDecodeUpNs / 1000.0}us)")

        val fullDecodeApiNs = ns { FlexBuffers.decode<BenchApiResponse>(bigBytes) }
        println("FlexBuffers.decode<ApiResponse>(): $fullDecodeApiNs ns  (= ${fullDecodeApiNs / 1000.0}us)")

        // String allocation cost (just create a string of equivalent length)
        val helloBytes = byteArrayOf(72, 101, 108, 108, 111)
        val stringAllocNs = ns { helloBytes.decodeToString() }  // "Hello"
        println("String(byte[5])               : $stringAllocNs ns")

        // ArrayList allocation
        val arrayListAllocNs = ns { ArrayList<Any>(20) }
        println("ArrayList<Any>(20)            : $arrayListAllocNs ns")

        // Empty object allocation reference
        val objAllocNs = ns { Any() }
        println("Any()                         : $objAllocNs ns")

        println("===========================================")
    }
}
