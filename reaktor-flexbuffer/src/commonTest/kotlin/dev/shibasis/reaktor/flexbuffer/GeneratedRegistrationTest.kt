@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.shibasis.reaktor.flexbuffer

import dev.shibasis.reaktor.core.InnerNestedData
import dev.shibasis.reaktor.core.InnerNestedDataFlexCoder
import dev.shibasis.reaktor.core.PropertySerialNamedStruct
import dev.shibasis.reaktor.core.SerialNamedStruct
import dev.shibasis.reaktor.core.SerialNamedStructFlexCoder
import dev.shibasis.reaktor.flexbuffer.core.FlexCoderRegistry
import dev.shibasis.reaktor.flexbuffer.core.FlexBuffers
import dev.shibasis.reaktor.flexbuffer.generated.ReaktorFlexbufferCoders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedRegistrationTest {
    @Test
    fun propertySerialNamesDriveGeneratedWireKeysAndRoundTrip() {
        try {
            FlexCoderRegistry.clear()
            ReaktorFlexbufferCoders.register()
            val original = PropertySerialNamedStruct(alphaSource = 41, zetaSource = "wire")

            val encoded = FlexBuffers.encode(original)
            val map = FlexBuffers.getRoot(encoded).toMap()

            assertEquals("a-wire", map.keyAsString(0))
            assertEquals("z-wire", map.keyAsString(1))
            assertEquals(original, FlexBuffers.decode<PropertySerialNamedStruct>(encoded))
        } finally {
            FlexCoderRegistry.clear()
            ReaktorFlexbufferCoders.register()
        }
    }

    @Test
    fun serialNameRegistrationRejectsAConflictingCoder() {
        try {
            FlexCoderRegistry.clear()
            FlexCoderRegistry.registerBySerialName("shared-wire-name", SerialNamedStructFlexCoder)
            FlexCoderRegistry.registerBySerialName("shared-wire-name", SerialNamedStructFlexCoder)

            assertFailsWith<IllegalArgumentException> {
                FlexCoderRegistry.registerBySerialName("shared-wire-name", InnerNestedDataFlexCoder)
            }
            assertTrue(
                FlexCoderRegistry.getBySerialName<Any>("shared-wire-name") ===
                    SerialNamedStructFlexCoder,
            )
        } finally {
            FlexCoderRegistry.clear()
            ReaktorFlexbufferCoders.register()
        }
    }

    @Test
    fun perCoderAndModuleRegistrationAreIdempotentAndSerialNameAware() {
        try {
            FlexCoderRegistry.clear()

            SerialNamedStructFlexCoder.register()
            SerialNamedStructFlexCoder.register()

            assertTrue(
                FlexCoderRegistry.get(SerialNamedStruct::class) === SerialNamedStructFlexCoder,
            )
            assertTrue(
                FlexCoderRegistry.getBySerialName<Any>(SerialNamedStruct.serializer().descriptor.serialName) ===
                    SerialNamedStructFlexCoder,
            )
            assertNull(
                FlexCoderRegistry.getBySerialName<Any>("dev.shibasis.reaktor.core.SerialNamedStruct"),
            )

            FlexCoderRegistry.clear()
            ReaktorFlexbufferCoders.register()
            ReaktorFlexbufferCoders.register()

            // EncodingTestClass.kt and RealisticBenchmarkModels.kt are distinct KSP origins.
            assertTrue(FlexCoderRegistry.get(InnerNestedData::class) === InnerNestedDataFlexCoder)
            assertTrue(FlexCoderRegistry.get(BenchUserProfile::class) === BenchUserProfileFlexCoder)
            assertTrue(
                FlexCoderRegistry.getBySerialName<Any>("reaktor.tests.serial-named-struct") ===
                    SerialNamedStructFlexCoder,
            )
        } finally {
            FlexCoderRegistry.clear()
            ReaktorFlexbufferCoders.register()
        }
    }
}
