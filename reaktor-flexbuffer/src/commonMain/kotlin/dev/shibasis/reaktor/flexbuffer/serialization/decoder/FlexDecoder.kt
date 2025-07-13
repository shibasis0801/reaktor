package dev.shibasis.reaktor.core.serialization.decoder

import com.google.flatbuffers.kotlin.Reference
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

class FlexDecoder(
    pointer: Reference,
    descriptor: SerialDescriptor
): Decoder, CompositeDecoder {
//    private val rawBuffer = FlexBuffer.GetBuffer(pointer)
//    val flexBuffer = getRoot(ArrayReadBuffer(rawBuffer))
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()
    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean {
        return true
    }

    var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return index++
    }
    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? {
        return deserializer.deserialize(this)
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return deserializer.deserialize(this)
    }

    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?
    ): T? {
        val name = descriptor.getElementName(index)

        return null
    }

    var Naa = ""
    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?
    ): T {
        Naa = descriptor.getElementName(index)
        return deserializer.deserialize(this)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {

    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        return 0
    }

    override fun decodeBoolean(): Boolean {
        return false
    }

    override fun decodeByte(): Byte {
        return 0
    }

    override fun decodeChar(): Char {

        return 'a'
    }

    override fun decodeDouble(): Double {

        return 0.0
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {

        return 0
    }

    override fun decodeFloat(): Float {

        return 0.0f
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        return this
    }

    override fun decodeInt(): Int {

        return 0
    }

    override fun decodeLong(): Long {

        return 0L
    }

    @ExperimentalSerializationApi
    override fun decodeNotNullMark(): Boolean {

        return false
    }

    @ExperimentalSerializationApi
    override fun decodeNull(): Nothing? {

        return null
    }

    override fun decodeShort(): Short {

        return 0
    }

    override fun decodeString(): String {

        return "booleanField"
    }

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val name = descriptor.getElementName(index)

        return false
    }

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        val name = descriptor.getElementName(index)

        return 0
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        val name = descriptor.getElementName(index)

        return 'a'
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        val name = descriptor.getElementName(index)

        return 0.0
    }


    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        val name = descriptor.getElementName(index)

        return 0.0f
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val name = descriptor.getElementName(index)

        return this
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        val name = descriptor.getElementName(index)

        return 0
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        val name = descriptor.getElementName(index)

        return 0L
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        val name = descriptor.getElementName(index)

        return 0
    }

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        val name = descriptor.getElementName(index)

        return ""
    }
}