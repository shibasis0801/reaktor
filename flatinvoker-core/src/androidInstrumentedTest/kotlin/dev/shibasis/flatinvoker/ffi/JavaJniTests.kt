package dev.shibasis.flatinvoker.core

import android.util.Log
import dev.shibasis.reaktor.io.network.http
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class JavaJniTests {
    suspend fun parseJsonToFlexBuffer() {
        // hack to not use moko-resources/compose-resources/building own
        val url = "http://10.0.2.2:8000/searchMyntra.json"
        val result = http.get(url)
        val body = result.body<String>()

        repeat(100) {
            val flexBuffer = FlexBuffer.Create()
            var cppTime: Long
            val timeForFlexParse = measureTime {
                cppTime = FlexBuffer.ParseJson(flexBuffer, body)
            }.inWholeMicroseconds

            val jsonParseTime = measureTime {
                Json.parseToJsonElement(body)
            }.inWholeMicroseconds

            Log.d("SHIBASIS", "kotlin-serialization: json-parse $jsonParseTime us")
            Log.d("SHIBASIS", "flexbuffers-total: $timeForFlexParse us")
            Log.d("SHIBASIS", "flexbuffers-cpp: $cppTime us")
        }

        val flexBuffer = FlexBuffer.Create()
        var cppTime: Long
        val timeForFlexParse = measureTime {
            cppTime = FlexBuffer.ParseJson(flexBuffer, body) / 1000
        }.inWholeMilliseconds

        val jsonParseTime = measureTime {
            Json.parseToJsonElement(body)
        }.inWholeMilliseconds

        Log.d("SHIBASIS", "kotlin-serialization: json-parse $jsonParseTime ms")
        Log.d("SHIBASIS", "flexbuffers-total: $timeForFlexParse ms")
        Log.d("SHIBASIS", "flexbuffers-cpp: $cppTime ms")
        // JSON Parse
        //        kotlin-serialization: json-parse 180201 us
        //        flexbuffers-total: 25324 us
        //        flexbuffers-cpp: 23068 us
        // non-trivial JNI overhead of 2ms
        // pure kotlin version benefits from caching, 180ms goes down to 30ms after 10 runs, 15ms after 50 runs
        // while flexbuf remains 20 throughout

        // For 550 kb json
        assertTrue("SHIBASIS: kotlin-serialization: json-parse $jsonParseTime ms") { jsonParseTime > 0 } // ~ 160 ms
        assertTrue("SHIBASIS: flexbuffers: $timeForFlexParse ms") { timeForFlexParse > 0 } // ~ 60 ms
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



