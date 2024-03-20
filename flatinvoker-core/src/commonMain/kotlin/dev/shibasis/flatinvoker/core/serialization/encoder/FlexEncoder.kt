package dev.shibasis.flatinvoker.core.serialization.encoder

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.util.Composite
import dev.shibasis.flatinvoker.core.serialization.util.EncodingStack
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.math.pow
import kotlin.time.measureTime

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

30% faster for simple cases
300% slower for complex cases
:/

Further optimisations could increase speed further.
1. Understand FlexBuffer creation in depth
2. Measure JNI overhead, replace JNI with Java FlexBuffers (official)
3. Read Crafting Interpreters
4. Find and optimise allocations, unnecessary functionalities, etc
5. Use typed arrays

Flexbuffers while encoding won't be very different from Json encoding algorithmically.
Both do additions in an array (byte array or char array)

Decoding should be much faster than Json.
For Json we need to parse the string and then convert it to the required type.
But for FlexBuffers, we can directly read the bytes and convert it to the required type.
So the parse steps should be reduced.



*/

@OptIn(ExperimentalUnsignedTypes::class)
fun test() = FlexBuffersBuilder().put(1)
class FlexEncoder: AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    val flexBuffer: Long = FlexBuffer.Create()
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
                val entry = stack.pop()
                require(entry.type == Composite.Class)
                FlexBuffer.EndMap(flexBuffer, entry.position)
            }
            StructureKind.MAP -> {
                val entry = stack.pop()
                require(entry.type == Composite.Map)
                FlexBuffer.EndMap(flexBuffer, entry.position)
            }
            StructureKind.LIST -> {
                val entry = stack.pop()
                require(entry.type == Composite.Vector)
                FlexBuffer.EndVector(flexBuffer, entry.position)
            }
            else -> {}
        }
    }
}

