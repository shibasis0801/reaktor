package dev.shibasis.flatinvoker.ipc

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class Person(val id: Int, val name: String, val age: Double)

class FlexEncoderTests {

    @Test
    fun testFlexEncoder() {
        val person = Person(1, "Shibasis", 27.9)
        val flexBuffer = encodeToFlexBuffer(person)
        FlexBuffer.Finish(flexBuffer)
        val array = FlexBuffer.GetBuffer(flexBuffer)

        assertTrue { array != null }

        val root = getRoot(ArrayReadBuffer(array!!))

        assertTrue(root.isMap)
        assertTrue(root.toMap()["id"].isInt)
        assertTrue(root.toMap()["id"].toInt() == 1)
        assertTrue(root.toMap()["name"].isString)
        assertTrue(root.toMap()["name"].toString() == "Shibasis")
        assertTrue(root.toMap()["age"].isFloat)
        assertTrue(root.toMap()["age"].toDouble() == 27.9)


        val json = Json.encodeToString(person)
        val jsonFlexBuffer = FlexBuffer.Create()
        FlexBuffer.ParseJson(jsonFlexBuffer, json)
        val jsonArray = FlexBuffer.GetBuffer(jsonFlexBuffer)

        assertTrue { jsonArray != null }

        val jsonRoot = getRoot(ArrayReadBuffer(jsonArray))

        assertTrue(jsonRoot.isMap)
        assertTrue(jsonRoot.toMap()["id"].isInt)
        assertTrue(jsonRoot.toMap()["id"].toInt() == 1)
        assertTrue(jsonRoot.toMap()["name"].isString)
        assertTrue(jsonRoot.toMap()["name"].toString() == "Shibasis")
        assertTrue(jsonRoot.toMap()["age"].isFloat)
        assertTrue(jsonRoot.toMap()["age"].toDouble() == 27.9)
    }

    @Test
    fun testArrayEncode() {
        val array = arrayListOf(1, 2, 3)

        val flexBuffer = encodeToFlexBuffer(array)
        FlexBuffer.Finish(flexBuffer)
        val retrieved = FlexBuffer.GetBuffer(flexBuffer)

        assertTrue { retrieved != null }
        val root = getRoot(ArrayReadBuffer(retrieved))

        assertTrue { root.isVector || root.isTypedVector }
        val vec = root.toVector()

        array.forEachIndexed { index, i ->
            assertEquals(i, vec[index].toInt())
        }
    }
}

