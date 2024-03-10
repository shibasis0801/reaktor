package dev.shibasis.flatinvoker.core.serialization.encoder

import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.util.Composite
import dev.shibasis.flatinvoker.core.serialization.util.EncodingStack
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule

/*
Focus should not be just on compact encoding
But should optimise for speed at minimum size.
Speed/Flexibility is more important than encoding size.

For size, I need to understand IndirectFields, TypedVectors, etc

The encode/decode must be the same algorithm as the one used by the JsonParser of FlexBuffers
100% parity needed, so that they can interop

Building this, I will be able to understand FlexBuffers in depth
And then write a series of articles

https://flatbuffers.dev/flexbuffers.html
*/
class FlexEncoder: AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    val flexBuffer = FlexBuffer.Create()
    val stack = EncodingStack()

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val name = descriptor.getElementName(index)
        stack.onEncodeElement(name)
        return true
    }


    /*
    ByteArrays work but are currently encoded as normal 8 bit arrays. Should be encoded as blobs.
    Currently Short/Byte/Int are encoded as Long.
    Maybe we can optimise that after deep diving into Flatbuffer protocol.
    I had read in the source that integers are always encoded efficiently, but deeper dive needed.
     */
    override fun encodeValue(value: Any) {
        val skipEncoding = stack.onEncodeValue(value)
        if (skipEncoding) return

        when (value) {
            is String -> FlexBuffer.String(flexBuffer, stack.field, value)
            is Char -> FlexBuffer.Int(flexBuffer, stack.field, value.code.toLong())
            is Int -> FlexBuffer.Int(flexBuffer, stack.field, value.toLong())
            is Byte -> FlexBuffer.Int(flexBuffer, stack.field, value.toLong())
            is Short -> FlexBuffer.Int(flexBuffer, stack.field, value.toLong())
            is Long -> FlexBuffer.Int(flexBuffer, stack.field, value)
            is Float -> FlexBuffer.Float(flexBuffer, stack.field, value)
            is Double -> FlexBuffer.Double(flexBuffer, stack.field, value)
            is Boolean -> FlexBuffer.Bool(flexBuffer, stack.field, value)
        }
    }

    override fun encodeNull() {
        FlexBuffer.Null(flexBuffer, null)
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        val name = stack.field
        when (descriptor.kind) {
            StructureKind.LIST -> {
                val start = FlexBuffer.StartVector(flexBuffer, name)
                stack.push(Composite.Vector, start)
            }
            StructureKind.MAP -> {
                val start = FlexBuffer.StartMap(flexBuffer, name)
                stack.push(Composite.Map, start)
            }
            else -> {}
        }
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val start = FlexBuffer.StartMap(flexBuffer, stack.field)
                stack.push(Composite.Class, start)
            }
            else -> {}
        }

        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val (type, position) = stack.pop()
                require(type == Composite.Class)
                FlexBuffer.EndMap(flexBuffer, position)
            }
            StructureKind.MAP -> {
                val (type, position) = stack.pop()
                require(type == Composite.Map)
                FlexBuffer.EndMap(flexBuffer, position)
            }
            StructureKind.LIST -> {
                val (type, position) = stack.pop()
                require(type == Composite.Vector)
                FlexBuffer.EndVector(flexBuffer, position)
            }
            else -> {}
        }
    }
}

