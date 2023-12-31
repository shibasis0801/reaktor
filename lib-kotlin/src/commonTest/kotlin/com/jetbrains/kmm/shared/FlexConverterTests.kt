package com.jetbrains.kmm.shared.data

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import com.google.flatbuffers.kotlin.ReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import kotlin.test.Test
import kotlin.test.assertEquals

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

}