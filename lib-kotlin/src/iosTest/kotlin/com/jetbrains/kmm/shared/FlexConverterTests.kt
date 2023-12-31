package com.jetbrains.kmm.shared.data

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.JSONParser
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.getRoot
import com.jetbrains.kmm.shared.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.utils.io.errors.PosixException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.decodeFromJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

//// This does not handle huge integers due to JS weird Number type, will need handling for actual json storage
//// object, array, string, number, boolean, and null
sealed interface JsonValue {
    // add JvmInline for all
    value class JsonString(val data: String): JsonValue
    value class JsonNumber(val data: Double): JsonValue
    value class JsonBoolean(val data: Boolean): JsonValue
    object JsonNull: JsonValue
    class JsonArray(val data: Array<JsonValue>): JsonValue
    class JsonObject(val data: HashMap<String, JsonValue>): JsonValue
}

fun JsonPrimitive.toJsonValue(): JsonValue {
    val value = this
    if (value is JsonNull) {
        return JsonValue.JsonNull
    } else if (value.isString) {
        return JsonValue.JsonString(value.content)
    } else {
        val number = value.doubleOrNull
        val boolean = value.booleanOrNull
        if (number != null) {
            return JsonValue.JsonNumber(number)
        } else if (boolean != null) {
            return JsonValue.JsonBoolean(boolean)
        }
    }
    throw Exception("Invalid Json Primitive")
}

fun JsonArray.toJsonValue(): JsonValue.JsonArray {
    return JsonValue.JsonArray(map {
        when(it) {
            is JsonPrimitive -> it.toJsonValue()
            is JsonObject -> it.toJsonValue()
            is JsonArray -> it.toJsonValue()
        }
    }.toTypedArray())
}

fun JsonObject.toJsonValue(): JsonValue.JsonObject {
    val data = hashMapOf<String, JsonValue>()

    entries.forEach {(key, value) ->
        when(value) {
            is JsonArray -> data[key] = value.toJsonValue()
            is JsonPrimitive -> data[key] = value.toJsonValue()
            is JsonObject -> data[key] = value.toJsonValue()
        }
    }

    return JsonValue.JsonObject(data)
}

class FlexConverterTestsDarwin {

    @Test
    fun testInt() {
        val builder = FlexBuffersBuilder()
        val original = 42

        IntConverter.toBinary(builder, original)

        val bytes = builder.finish()

        val recovered = IntConverter.fromBinary(getRoot(bytes))
        assertEquals(original, recovered)
    }


    @Test
    fun testString() {
        val builder = FlexBuffersBuilder()
        val original = "Hello World"

        StringConverter.toBinary(builder, original)

        val bytes = builder.finish()

        val recovered = StringConverter.fromBinary(getRoot(bytes))
        assertEquals(original, recovered)
    }

    @Test
    fun testPerson() {
        val person = Person(1, "shibasis")
        registerConverters()

        val converter = FlexConverterRegistry.get<Person>()

        val flexBuffer = FlexBuffersBuilder()
        converter.toBinary(flexBuffer, person)
        val bytes = flexBuffer.finish()

        val recovered = converter.fromBinary(getRoot(bytes))
        assertEquals(recovered.id, person.id)
        assertEquals(recovered.name, person.name)
    }

    @Test
    fun testIntArrayList() {
        val builder = FlexBuffersBuilder()
        val original = arrayListOf(1, 2, 3, 4, 5)

        IntArrayFlexConverter.toBinary(builder, original)

        val bytes = builder.finish()

        val recovered = IntArrayFlexConverter.fromBinary(getRoot(bytes))
        assertEquals(recovered.size, original.size)
        recovered.forEachIndexed { idx, item ->
            assertEquals(item, original[idx])
        }
        assertEquals(original, recovered)
    }

    @Test
    fun testSearchFlex() {
        runBlocking {
            val url = "http://localhost:8000/search.json"
            val result = httpClient.get(url)
            assertEquals(result.status.value, 200)
            val parser = JSONParser()
            lateinit var root: Reference
            val timeForFlexParse = measureTime {
                val bytes = result.body<ByteArray>()
                root = parser.parse(bytes)
            }
            val time = timeForFlexParse.inWholeMilliseconds
            val size = root.toByteArray().size
            assertTrue { timeForFlexParse.isFinite() }
        }
    }


    @Test
    fun testSearchJSON() {
        runBlocking {
            val url = "http://localhost:8000/search.json"
            val result = httpClient.get(url)
            assertEquals(result.status.value, 200)

            lateinit var root: JsonElement
            val timeForJsonParse = measureTime {
                val bytes = result.body<String>()
                root = Json.parseToJsonElement(bytes)
            }

            val map = hashMapOf<String, String>()
            root.jsonObject.entries.forEach { (key, value) ->
                try {
                    val data = value.jsonPrimitive
                    if (data.isString) {
                        map[key] = data.content
                    }
                } catch (_: Exception) {}
            }

            lateinit var value: JsonValue
            val timeForJsonElementConversion = measureTime {
                value = root.jsonObject.toJsonValue()
            }


//            val person: Person = Json.decodeFromJsonElement(root)


            val time = timeForJsonParse.inWholeMilliseconds // 60 ms
            val timeTraverse = timeForJsonElementConversion.inWholeMilliseconds // 20 ms
            assertTrue { timeForJsonParse.isFinite() }
        }
    }
}