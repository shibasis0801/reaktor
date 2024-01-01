package com.jetbrains.kmm.shared

import android.util.Log
import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

        val answer = result[0].toInt()
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

    @Test
    fun testExecute() {
        val command = FlexBuffersBuilder().apply {
            val start = startMap()
            set("className", "Sum")
            set("functionName", "add")

            val vStart = startMap()
            val vvStart = startVector()
            put(45)
            put(55)
            endVector("data", vvStart)
            endMap(vStart, "payload")

            endMap(start)
        }.finish()

        //
        val result = JavaJni.execute(command)
        //

        val sum = getRoot(result).toVector()

        val className = sum[0].toString()
        val functionName = sum[1].toString()
        val payloadResult = sum[2].toInt()

        assertTrue(className == "Sum")
        assertTrue(functionName == "add")
        assertTrue(payloadResult == 100)
    }

    @Test
    fun testParseJsonToFlexBuffer() {
        runBlocking {
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


            // For 550 kb json
            Log.d("SHIBASIS: JSON to FlexBuffer in a DirectByteBuffer", resultTime.toString()) // ~ 60ms
            Log.d("SHIBASIS: Copy FlexBuffer from DBB to ReadBuffer", timeForFlexParse.toString()) // ~ 1ms
            Log.d("SHIBASIS: Pure JSON Parse using kotlin-serialization", jsonParseTime.toString()) // ~ 161ms
            assertTrue { resultTime < 0 }
            assertTrue { timeForFlexParse < 0 }
        }
    }

}
// todo - make merging into main impossible without passing tests









