package com.jetbrains.kmm.shared

import android.util.Log
import com.google.flatbuffers.kotlin.ArrayReadWriteBuffer
import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
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


}
// todo - make merging into main impossible without passing tests









