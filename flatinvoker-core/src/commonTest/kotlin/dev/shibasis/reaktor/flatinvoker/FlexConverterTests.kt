package dev.shibasis.reaktor.flatinvoker

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.JSONParser
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.flatinvoker.data.FlexConverterRegistry
import dev.shibasis.reaktor.flatinvoker.data.IntArrayFlexConverter
import dev.shibasis.reaktor.flatinvoker.data.IntConverter
import dev.shibasis.reaktor.flatinvoker.data.Person
import dev.shibasis.reaktor.flatinvoker.data.StringConverter
import dev.shibasis.reaktor.flatinvoker.data.registerConverters
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

//// This does not handle huge integers due to JS weird Number type, will need handling for actual json storage
//// object, array, string, number, boolean, and null
sealed interface JsonValue {
    // add JvmInline for all
    @JvmInline
    value class JsonString(val data: String): JsonValue
    @JvmInline
    value class JsonNumber(val data: Double): JsonValue
    @JvmInline
    value class JsonBoolean(val data: Boolean): JsonValue
    object JsonNull: JsonValue
    class JsonArray(val data: Array<JsonValue>): JsonValue
    class JsonObject(val data: HashMap<String, JsonValue>): JsonValue
}

inline fun JsonPrimitive.toJsonValue(): JsonValue {
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

// assumes you are inside a map
inline fun FlexBuffersBuilder.insertJsonPrimitive(key: String, value: JsonValue) {
    println("SHIBASIS: Primitive Key: $key, Value: $value")
    when(value) {
        is JsonValue.JsonBoolean -> { this[key] = value.data }
        is JsonValue.JsonNumber -> { this[key] = value.data }
        is JsonValue.JsonString -> { this[key] = value.data }
        JsonValue.JsonNull -> { /* Null values are ignored */ }
        is JsonValue.JsonArray, is JsonValue.JsonObject -> { /* Impossible, fix hierarchy later */ }
    }
    println("SHIBASIS:Current Size ${buffer.data().size}")
}
inline fun FlexBuffersBuilder.insertJsonPrimitive(key: String, value: JsonPrimitive) {
    val data = value.toJsonValue()
    insertJsonPrimitive(key, data)
}

fun JsonArray.toJsonValue(): JsonValue.JsonArray {
    return JsonValue.JsonArray(map {
        when (it) {
            is JsonPrimitive -> it.toJsonValue()
            is JsonObject -> it.toJsonValue()
            is JsonArray -> it.toJsonValue()
        }
    }.toTypedArray())
}


fun FlexBuffersBuilder.insertJsonArray(key: String? = null, value: JsonValue.JsonArray) {
    println("SHIBASIS: Array Key: $key")
    val start = startVector()
    value.data.forEach {
        when(it) {
            is JsonValue.JsonBoolean -> {
                put(it.data)
            }

            is JsonValue.JsonNumber -> {
                put(it.data)
            }

            is JsonValue.JsonString -> {
                put(it.data)
            }

            JsonValue.JsonNull -> { /* todo we can't ignore null values here */ }

            is JsonValue.JsonArray -> {
                insertJsonArray(null, it)
            }

            is JsonValue.JsonObject -> {
                insertJsonObject(null, it)
            }
        }
    }
    endVector(key, start)
    println("SHIBASIS:Current Size ${buffer.data().size}")
}
inline fun FlexBuffersBuilder.insertJsonArray(key: String? = null, value: JsonArray) {
    val data = value.toJsonValue()
    insertJsonArray(key, data)
}

// todo Exponential Increase in maps, JSON parse also does the same
// Need to debug
fun FlexBuffersBuilder.insertJsonObject(key: String? = null, value: JsonValue.JsonObject) {
    val start = startMap()
    println("SHIBASIS:Map Key: $key")
    value.data.map {(k, v) ->
        when(v) {
            is JsonValue.JsonBoolean, is JsonValue.JsonString, is JsonValue.JsonNumber, JsonValue.JsonNull -> insertJsonPrimitive(k, v)
            is JsonValue.JsonObject -> insertJsonObject(k, v)
            is JsonValue.JsonArray -> insertJsonArray(k, v)
        }
    }
    endMap(start, key)
    println("SHIBASIS:Current Size ${buffer.data().size}")
}

inline fun FlexBuffersBuilder.insertJsonObject(key: String? = null, value: JsonObject) {
    val data = value.toJsonValue()
    insertJsonObject(key, data)
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


class FlexConverterTests {

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
    fun testParseJsonToFlexBuffer() {
        runBlocking {
            val url = "http://localhost:8000/search.json"
            val result = httpClient.get(url)
            val timeForFlexParse = measureTime {
                val bytes = result.body<String>()

            }
            val time = timeForFlexParse.inWholeMilliseconds
            assertTrue { timeForFlexParse.isFinite() }
        }
    }
}