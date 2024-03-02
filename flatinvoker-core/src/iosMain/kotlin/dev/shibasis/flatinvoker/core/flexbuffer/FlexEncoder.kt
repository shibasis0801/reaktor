package dev.shibasis.flatinvoker.core.flexbuffer

import dev.shibasis.reaktor.native.Flex_Bool
import dev.shibasis.reaktor.native.Flex_Create
import dev.shibasis.reaktor.native.Flex_Double
import dev.shibasis.reaktor.native.Flex_EndMap
import dev.shibasis.reaktor.native.Flex_EndVector
import dev.shibasis.reaktor.native.Flex_Int
import dev.shibasis.reaktor.native.Flex_Null
import dev.shibasis.reaktor.native.Flex_StartMap
import dev.shibasis.reaktor.native.Flex_StartVector
import dev.shibasis.reaktor.native.Flex_String
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer

/*
Focus should not be just on compact encoding
But should optimise for speed at minimum size.
Speed/Flexibility is more important than encoding size.

For size, I need to understand IndirectFields, TypedVectors, etc

The encode/decode must be the same algorithm as the one used by the JsonParser of FlexBuffers
100% parity needed, so that they can interop

Building this, I will be able to understand FlexBuffers in depth
And then write a series of articles


 */
class FlexEncoder: AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    val flexBuffer = Flex_Create()

    // need to keep these in a stack
    var vectorStart: ULong = 0u
    var mapStart: ULong = 0u
    var inVector = false

    var fieldName: String = ""
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        fieldName = descriptor.getElementName(index)
        return super.encodeElement(descriptor, index)
    }

    override fun encodeValue(value: Any) {
        val fieldName = if (inVector) null else this.fieldName
        when (value) {
            is String -> Flex_String(flexBuffer, fieldName, value)
            is Int -> Flex_Int(flexBuffer, fieldName, value.toLong())
            is Long -> Flex_Int(flexBuffer, fieldName, value)
            is Double -> Flex_Double(flexBuffer, fieldName, value)
            is Boolean -> Flex_Bool(flexBuffer, fieldName, value)
        }
    }

    override fun encodeNull() {
        Flex_Null(flexBuffer, null)
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        vectorStart = Flex_StartVector(flexBuffer, null)
        inVector = true
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.MAP, StructureKind.OBJECT -> {
                mapStart = Flex_StartMap(flexBuffer, null)
                inVector = false
                return this
            }
            // according to code, StructureKind.LIST should never come here, instead beginCollection
            else -> return super.beginStructure(descriptor)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.MAP, StructureKind.OBJECT -> {
                Flex_EndMap(flexBuffer, mapStart)
                inVector = true
            }
            StructureKind.LIST -> {
                Flex_EndVector(flexBuffer, vectorStart)
                inVector = false
            }
            else -> {}
        }
    }
}

fun<T> encodeToFlexBuffer(serializer: SerializationStrategy<T>, value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.flexBuffer
}

inline fun <reified T> encodeToFlexBuffer(value: T) = encodeToFlexBuffer(serializer(), value)