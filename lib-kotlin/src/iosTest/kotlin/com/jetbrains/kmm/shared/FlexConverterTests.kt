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
        when(it) {
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
            var size = 0
            var body = ""
            val timeForJsonParse = measureTime {
                body = result.body<String>()
                size = body.length
                root = Json.parseToJsonElement(body)
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

            lateinit var value: JsonValue.JsonObject
            val timeForJsonElementConversion = measureTime {
                value = root.jsonObject.toJsonValue()
            }


            val buffer = FlexBuffersBuilder()
            lateinit var bytes: ReadBuffer
            val timeForFlexConversion = measureTime {
                buffer.insertJsonObject(null, value)
                bytes = buffer.finish()
            }

            val json = getRoot(bytes)
            assertTrue { json.isMap }

            // Test Double Working
//            val totalPLAShown = json.toMap()["totalPLAShown"]
//            val total = totalPLAShown.toDouble()
//            assertTrue { total == 4.0 }

            // Test
//            val products = json.toMap()["products"]
//            assertTrue { products.isVector }
//
//            val first = products.toVector()[0]
//            assertTrue { first.isMap }
//
//            val images = first["images"]
//            assertTrue { images.isVector }
//
//            val firstImage = images.toVector()[0]
//            assertTrue { firstImage.isMap }
//
//            val src = firstImage["src"]
//            val link = "http://assets.myntassets.com/assets/images/10673544/2019/9/24/6b9c7688-7ca2-4d11-9e5b-a3745ecd8f761569310358973-The-Indian-Garage-Co-Men-Shirts-8481569310357131-1.jpg"
//            assertTrue { src.isString }
//            assertTrue { src.toString() == link }


//            val person: Person = Json.decodeFromJsonElement(root)


            val time = timeForJsonParse.inWholeMicroseconds // 60 ms
            val timeTraverse = timeForJsonElementConversion.inWholeMicroseconds // 20 ms
            val timeFlex = timeForFlexConversion.inWholeMicroseconds // 0.4ms
            val flexSize = bytes.data().size
            val data = JSONParser().parse("""
                {
  "filterValues": [
          {
            "id": "XS",
            "value": "XS",
            "count": 7,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "S",
            "value": "S",
            "count": 41,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "M",
            "value": "M",
            "count": 77,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "L",
            "value": "L",
            "count": 77,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "XL",
            "value": "XL",
            "count": 76,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "XXL",
            "value": "XXL",
            "count": 61,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "31",
            "value": "31",
            "count": 27,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "32",
            "value": "32",
            "count": 46,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "33",
            "value": "33",
            "count": 137,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "34",
            "value": "34",
            "count": 122,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "35",
            "value": "35",
            "count": 432,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "36",
            "value": "36",
            "count": 7742,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "37",
            "value": "37",
            "count": 797,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "37.5",
            "value": "37.5",
            "count": 6,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "38",
            "value": "38",
            "count": 56605,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "38.5",
            "value": "38.5",
            "count": 113,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "39",
            "value": "39",
            "count": 32723,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "39.5",
            "value": "39.5",
            "count": 78,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "40",
            "value": "40",
            "count": 80341,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "40.5",
            "value": "40.5",
            "count": 137,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "41",
            "value": "41",
            "count": 1139,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "41.5",
            "value": "41.5",
            "count": 39,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "42",
            "value": "42",
            "count": 73737,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "42.5",
            "value": "42.5",
            "count": 142,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "43",
            "value": "43",
            "count": 1312,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "43.5",
            "value": "43.5",
            "count": 34,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "44",
            "value": "44",
            "count": 67109,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "44.5",
            "value": "44.5",
            "count": 77,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "45",
            "value": "45",
            "count": 1190,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "45.5",
            "value": "45.5",
            "count": 3,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "46",
            "value": "46",
            "count": 34882,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "47",
            "value": "47",
            "count": 455,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "48",
            "value": "48",
            "count": 7736,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "49",
            "value": "49",
            "count": 353,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "50",
            "value": "50",
            "count": 2972,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "51",
            "value": "51",
            "count": 161,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "52",
            "value": "52",
            "count": 2159,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "54",
            "value": "54",
            "count": 1505,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "56",
            "value": "56",
            "count": 378,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "58",
            "value": "58",
            "count": 310,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "60",
            "value": "60",
            "count": 64,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "62",
            "value": "62",
            "count": 7,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "64",
            "value": "64",
            "count": 8,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "66",
            "value": "66",
            "count": 1,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "68",
            "value": "68",
            "count": 4,
            "meta": "",
            "pLevel": ""
          },
          {
            "id": "74",
            "value": "74",
            "count": 1,
            "meta": "",
            "pLevel": ""
          }
        ]
}
            """.trimIndent())

            assertTrue { timeForJsonParse.isFinite() }
        }
    }
}