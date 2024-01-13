package dev.shibasis.reaktor.flatinvoker

import android.util.Log
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.flatinvoker.networkCache.HugeBenchmark
import dev.shibasis.reaktor.flatinvoker.networkCache.NetworkCache
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class JavaJniTests {
    @Test
    fun testJavaCppFlexBufferIntegerSum() {
        val array = FlexBuffersBuilder().apply {
            val start = startVector()
            put(45)
            put(55)
            endVector(start)
        }.finish()

        val result = JavaJni.sendMessage(array)

        val ref = getRoot(result).toVector()
        val sum = ref[2].toInt()

        assertTrue("Sum is 100", sum == 100)
    }

    @Test
    fun testByteBufferTransfer() {
        val buffer = JavaJni.getByteBuffer()
        println("ByteBuffer offset -> ${buffer.arrayOffset()}")
        assertTrue(buffer.arrayOffset() != 0)
    }

    @Test
    fun testEchoByteBuffer() {
        val buffer = ByteBuffer.allocateDirect(1)
        val newBuffer = ByteBuffer.allocateDirect(1)
        newBuffer.put(9)
        val result = JavaJni.echoByteBuffer(buffer)
        assertTrue(true)
    }

    suspend fun parseJsonToFlexBuffer() {
        val url = "http://192.168.0.247:8000/search.json"
        val result = httpClient.get(url)
        lateinit var buffer: ByteBuffer
        lateinit var flexBuffer: ReadBuffer
        val body = result.body<String>()
        val timeForFlexParse = measureTime {
            buffer = JavaJni.parseJson(body)
        }.inWholeMicroseconds
        // 50 - 60 ms for 550kb json

        val jsonParseTime = measureTime {
            Json.parseToJsonElement(body)
        }.inWholeMicroseconds

        val resultTime = measureTime {
            flexBuffer = buffer.toReadBuffer()
        }.inWholeMicroseconds
        // < 1 ms

        val root = getRoot(flexBuffer)
        assertTrue { root.isMap }

        val responseType = root.toMap()["responseType"]
        assert(responseType.isString)
        assertEquals(responseType.toString(), "LIST")

        Log.e("SHIBASIS", "JSON to FlexBuffer in a DirectByteBuffer: $resultTime")
        Log.e("SHIBASIS", "Copy FlexBuffer from DBB to ReadBuffer: $timeForFlexParse")
        Log.e("SHIBASIS", "Pure JSON Parse using kotlin-serialization: $jsonParseTime")

        // For 550 kb json
        assertTrue("SHIBASIS: JSON to FlexBuffer in a DirectByteBuffer: $resultTime") { resultTime > 0 } // ~ 60ms
        assertTrue("SHIBASIS: Copy FlexBuffer from DBB to ReadBuffer: $timeForFlexParse") { timeForFlexParse > 0 } // ~ 1ms
        assertTrue("SHIBASIS: Pure JSON Parse using kotlin-serialization: $jsonParseTime") { jsonParseTime > 0 } // ~ 161ms
    }

    @Test
    fun testParseJsonToFlexBuffer() {
        runBlocking {
           parseJsonToFlexBuffer()
        }
    }

    @Test
    fun testParseJsonToFlexBufferAsync() {
        runBlocking(Dispatchers.IO) {
            parseJsonToFlexBuffer()
        }
    }

    @Test
    fun measureConversionTime() {
        runBlocking {
            val url = "http://192.168.0.247:8000/search.json"
            val result = httpClient.get(url)
            lateinit var json: JsonElement
            val body = result.body<ByteArray>()
//            val body = byteArrayOf()
            val cache = HugeBenchmark(
                id = 1,
                data = body,
                url = url,
                ttl = 1000,
                timestamp = 1000,
                cacheKey = "key"
            )
            val jsonElementTime = measureTime {
                json = Json.encodeToJsonElement(cache)
            }.inWholeMilliseconds

            println("Huge JSON Element Time: $jsonElementTime")
            assertTrue(jsonElementTime > 0)

            val flexBuffer = FlexBuffersBuilder()
            lateinit var readBuffer: ReadBuffer
            val flexBufferTime = measureTime {
                flexBuffer.apply {
                    putMap {
                        set("id", 1)
                        set("data", body)
                        set("data1", body)
                        set("data2", body)
                        set("data3", body)
                        set("data4", body)
                        set("data5", body)
                        set("url", url)
                        set("ttl", 1000)
                        set("timestamp", 1000)
                        set("cacheKey", "key")
                    }
                }
                readBuffer = flexBuffer.finish()
            }.inWholeMilliseconds

            println("Huge FlexBuffer Time: $flexBufferTime")
            assertTrue(flexBufferTime > 0)

            val networkCache = NetworkCache(
                id = 1,
                url = url,
                ttl = 1000,
                timestamp = 1000,
                cacheKey = "key"
            )
            val networkCacheTime = measureTime {
                Json.encodeToJsonElement(networkCache)
            }.inWholeMicroseconds
            println("Network Cache Time: $networkCacheTime")
            assertTrue(networkCacheTime > 0)
            // convert into a flexbuffer and check again
            val networkCacheFlexBufferTime = measureTime {
//                val flexBuffer = FlexBuffersBuilder()
//                flexBuffer.apply {
//                    putMap {
//                        set("id", networkCache.id)
//                        set("url", networkCache.url)
//                        set("data", networkCache.data)
//                        set("ttl", networkCache.ttl)
//                        set("timestamp", networkCache.timestamp)
//                        set("cacheKey", networkCache.cacheKey)
//                    }
//                }
//                val readBuffer = flexBuffer.finish()
            }.inWholeNanoseconds
            println("Network Cache FlexBuffer Time: $networkCacheFlexBufferTime")
            assertTrue(networkCacheFlexBufferTime > 0)

            val minimumTime = measureTime{}.inWholeMilliseconds

        }
    }

    @Test
    fun testingCustomSerialization() {
        val serializer = NetworkCache.serializer()
        println("Serializer: ${serializer.descriptor}")
        assertTrue(true)
    }

}
// todo - make merging into main impossible without passing tests




