package dev.shibasis.flatinvoker.core

import android.util.Log
import dev.shibasis.reaktor.io.network.httpClient
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
        val url = "https://raw.githubusercontent.com/shibasis0801/flatInvoker/main/flatinvoker-core/src/commonMain/resources/searchMyntra.json";
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



