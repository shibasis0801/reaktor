package dev.shibasis.flatinvoker.core.serialization.encoder

import com.google.flatbuffers.kotlin.FlexBuffersBuilder
import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.util.COMPOSITE_CLASS
import dev.shibasis.flatinvoker.core.serialization.util.COMPOSITE_MAP
import dev.shibasis.flatinvoker.core.serialization.util.COMPOSITE_VECTOR
import dev.shibasis.flatinvoker.core.serialization.util.EncodingStack
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
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

30% faster for simple cases
300% slower for complex cases

For large data,
Protobuf is 20% faster than JSON
FlexBuffers-CPP is slower 3 times even with JNI overhead
FlexBuffers-Java is slower 6 times

todo critical need to profile the cpp version to make it comparable to json in encoding speed.
if i can parallelize it would be better (unlikely as this is a sequential operation, if I can predict size/offset then I can parallelize)
but to profile, e2e app needed.

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

https://proandroiddev.com/kotlin-cleaning-java-bytecode-before-release-9567d4c63911

FlexEncoders must also be pooled.
Try to make this re-entrant and thread safe
Would save time in high frequency transfers

Move to Encoder, CompositeEncoder
AbstractEncoder only seems apt for simple understanding

*/

class FlexEncoderHalfMigrated: Encoder, CompositeEncoder {
    override val serializersModule = EmptySerializersModule()
    val flexBuffer: Long = FlexBuffer.Create()
    val stack = EncodingStack()

    inline fun onEncodeValue(value: Any?): Int? {
        return if (stack.onEncodeValue(value)) null else 0
    }

    /** ----------------    Encoder Methods      ---------------- */
    override fun encodeNull() {
        onEncodeValue(null) ?: return
        FlexBuffer.Null(flexBuffer, null)
    }
    override fun encodeBoolean(value: Boolean) {
        onEncodeValue(value) ?: return
        FlexBuffer.Bool(flexBuffer, stack.current?.fieldName, value)
    }

    override fun encodeByte(value: Byte) {
        onEncodeValue(value) ?: return
        FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
    }

    override fun encodeChar(value: Char) {
        onEncodeValue(value) ?: return
        FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
    }

    override fun encodeDouble(value: Double) {
        onEncodeValue(value) ?: return
        FlexBuffer.Double(flexBuffer, stack.current?.fieldName, value)
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        TODO("Not yet implemented")
    }

    override fun encodeFloat(value: Float) {
        onEncodeValue(value) ?: return
        FlexBuffer.Float(flexBuffer, stack.current?.fieldName, value)
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        TODO("Not yet implemented")
    }

    override fun encodeInt(value: Int) {
        onEncodeValue(value) ?: return
        FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
    }

    override fun encodeLong(value: Long) {
        onEncodeValue(value) ?: return
        FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value)
    }

    override fun encodeShort(value: Short) {
        onEncodeValue(value) ?: return
        FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
    }

    override fun encodeString(value: String) {
        onEncodeValue(value) ?: return
        FlexBuffer.String(flexBuffer, stack.current?.fieldName, value)
    }

    /** ---------------- Composite Encoder Methods ---------------- */

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
        encodeBoolean(value)
    }

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
        encodeByte(value)
    }

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
        encodeChar(value)
    }

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
        encodeDouble(value)
    }

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
        encodeFloat(value)
    }

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
        return encodeInline(descriptor)
    }

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        encodeInt(value)
    }

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
        encodeLong(value)
    }

    @ExperimentalSerializationApi
    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        if (encodeElement(descriptor, index))
            encodeNullableSerializableValue(serializer, value)
    }

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T
    ) {
        if (encodeElement(descriptor, index))
            encodeSerializableValue(serializer, value)
    }

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
        encodeShort(value)
    }

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
        encodeString(value)
    }


    // todo profiler marked slow
    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        val name = stack.current?.fieldName
        val name1 = descriptor.getElementName(0)
        when (descriptor.kind) {
            StructureKind.LIST -> {
                val start = FlexBuffer.StartVector(flexBuffer, name)
                stack.push(COMPOSITE_VECTOR, start)
            }
            StructureKind.MAP -> {
                val start = FlexBuffer.StartMap(flexBuffer, name)
                stack.push(COMPOSITE_MAP, start)
            }
            else -> {}
        }
        return this
    }

    // todo profiler marked slow
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                // Check if it is possible to avoid a root map.
                val start = FlexBuffer.StartMap(flexBuffer, stack.current?.fieldName)
                stack.push(COMPOSITE_CLASS, start)
            }
            else -> {}
        }
        return this
    }

    // todo profiler marked slow
    override fun endStructure(descriptor: SerialDescriptor) {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val entry = stack.pop()
                FlexBuffer.EndMap(flexBuffer, entry.position)
            }
            StructureKind.MAP -> {
                val entry = stack.pop()
                FlexBuffer.EndMap(flexBuffer, entry.position)
            }
            StructureKind.LIST -> {
                val entry = stack.pop()
                FlexBuffer.EndVector(flexBuffer, entry.position)
            }
            else -> {}
        }
    }

    /** ---------------- Old Methods ---------------- */

    // todo profiler marked slow
    fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        // todo profiler marked slow
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
    // todo profiler marked slow
    fun encodeValue(value: Any) {
        val skipEncoding = stack.onEncodeValue(value)
        if (skipEncoding) return

        when (value) {
            is String -> FlexBuffer.String(flexBuffer, stack.current?.fieldName, value)
            is Char -> FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.code.toLong())
            is Int -> FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
            is Byte -> FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
            is Short -> FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value.toLong())
            is Long -> FlexBuffer.Int(flexBuffer, stack.current?.fieldName, value)
            is Float -> FlexBuffer.Float(flexBuffer, stack.current?.fieldName, value)
            is Double -> FlexBuffer.Double(flexBuffer, stack.current?.fieldName, value)
            is Boolean -> FlexBuffer.Bool(flexBuffer, stack.current?.fieldName, value)
        }
    }
}

/*
Timings:

EncodingComplexCase
> March 30, 2024 (2-3 times slower to encode than JSON)
    > 1 iteration
        FlexBuffer Average: 14197
        ProtoBuf Average: 2725
        Json Average: 7536

    > 100 iterations
        FlexBuffer Average: 3616
        ProtoBuf Average: 1009
        Json Average: 1636

    > 1000 iterations
        FlexBuffer Average: 2827
        ProtoBuf Average: 1068
        Json Average: 1364
 */

