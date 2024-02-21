package dev.shibasis.reaktor.flatinvoker

import android.util.Log
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTime

class JavaJniTests {
    suspend fun parseJsonToFlexBuffer() {
        val url = "http://10.0.2.2:8000/search.json"
        val result = httpClient.get(url)
        val body = result.body<String>()

        val timeForFlexParse = measureTime {
            JavaJni.parseJson(body)
        }.inWholeMilliseconds

        val jsonParseTime = measureTime {
            Json.parseToJsonElement(body)
        }.inWholeMilliseconds

        Log.e("SHIBASIS", "kotlin-serialization: json-parse $jsonParseTime ms")
        Log.e("SHIBASIS", "flexbuffers: $timeForFlexParse ms")

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



