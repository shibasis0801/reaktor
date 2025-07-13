package dev.shibasis.reaktor.core.serialization.decoder


import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule


/**
This decoder is no-op, but it will print the order of the calls to a Decoder.
Very important to understand call stack for decoding any class

Element mapOfStringToInt
Element arrayOfInt
Element mutableMapOfStringToList
CallTraceDecoder: decodeSerializableValue
CallTraceDecoder: beginStructure dev.shibasis.flatinvoker.core.EncodingSimpleCase  CLASS
	Element mapOfStringToInt
	Element arrayOfInt
	Element mutableMapOfStringToList
	CallTraceDecoder: decodeSequentially
	CallTraceDecoder: decodeSerializableElement mapOfStringToInt
	CallTraceDecoder: beginStructure kotlin.collections.LinkedHashMap mapOfStringToInt MAP
		Element 0
		Element 1
		CallTraceDecoder: decodeSequentially
		CallTraceDecoder: decodeCollectionSize mapOfStringToInt
		CallTraceDecoder: decodeSerializableElement 0
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 1
		CallTraceDecoder: decodeInt
		CallTraceDecoder: decodeSerializableElement 2
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 3
		CallTraceDecoder: decodeInt
		CallTraceDecoder: decodeSerializableElement 4
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 5
		CallTraceDecoder: decodeInt
		CallTraceDecoder: endStructure kotlin.collections.LinkedHashMap
	CallTraceDecoder: decodeSerializableElement arrayOfInt
	CallTraceDecoder: beginStructure kotlin.collections.ArrayList arrayOfInt LIST
		Element 0
		CallTraceDecoder: decodeSequentially
		CallTraceDecoder: decodeCollectionSize arrayOfInt
		CallTraceDecoder: decodeSerializableElement 0
		CallTraceDecoder: decodeInt
		CallTraceDecoder: decodeSerializableElement 1
		CallTraceDecoder: decodeInt
		CallTraceDecoder: decodeSerializableElement 2
		CallTraceDecoder: decodeInt
		CallTraceDecoder: decodeSerializableElement 3
		CallTraceDecoder: decodeInt
		CallTraceDecoder: decodeSerializableElement 4
		CallTraceDecoder: decodeInt
		CallTraceDecoder: endStructure kotlin.collections.ArrayList
	CallTraceDecoder: decodeSerializableElement mutableMapOfStringToList
	CallTraceDecoder: beginStructure kotlin.collections.LinkedHashMap mutableMapOfStringToList MAP
		Element 0
		Element 1
		CallTraceDecoder: decodeSequentially
		CallTraceDecoder: decodeCollectionSize mutableMapOfStringToList
		CallTraceDecoder: decodeSerializableElement 0
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 1
		CallTraceDecoder: beginStructure kotlin.collections.ArrayList 1 LIST
			Element 0
			CallTraceDecoder: decodeSequentially
			CallTraceDecoder: decodeCollectionSize 1
			CallTraceDecoder: endStructure kotlin.collections.ArrayList
		CallTraceDecoder: decodeSerializableElement 2
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 3
		CallTraceDecoder: beginStructure kotlin.collections.ArrayList 3 LIST
			Element 0
			CallTraceDecoder: decodeSequentially
			CallTraceDecoder: decodeCollectionSize 3
			CallTraceDecoder: endStructure kotlin.collections.ArrayList
		CallTraceDecoder: decodeSerializableElement 4
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 5
		CallTraceDecoder: beginStructure kotlin.collections.ArrayList 5 LIST
			Element 0
			CallTraceDecoder: decodeSequentially
			CallTraceDecoder: decodeCollectionSize 5
			CallTraceDecoder: endStructure kotlin.collections.ArrayList
		CallTraceDecoder: decodeSerializableElement 6
		CallTraceDecoder: decodeString
		CallTraceDecoder: decodeSerializableElement 7
		CallTraceDecoder: beginStructure kotlin.collections.ArrayList 7 LIST
			Element 0
			CallTraceDecoder: decodeSequentially
			CallTraceDecoder: decodeCollectionSize 7
			CallTraceDecoder: endStructure kotlin.collections.ArrayList
		CallTraceDecoder: endStructure kotlin.collections.LinkedHashMap
	CallTraceDecoder: endStructure dev.shibasis.flatinvoker.core.EncodingSimpleCase
 */
class CallTraceDecoder(
    private val descriptor: SerialDescriptor,
    val prefix: String = "",
    var name: String? = null
): Decoder, CompositeDecoder {
    inline fun log(msg: String, tag: String = "CallTraceDecoder: ") {
        println("$prefix$tag$msg")
    }
    override val serializersModule: SerializersModule
        get() = EmptySerializersModule()

    init {
        repeat(descriptor.elementsCount) {
            log("Element ${descriptor.getElementName(it)}", "")
        }
    }

    @ExperimentalSerializationApi
    override fun decodeSequentially(): Boolean {
        log("decodeSequentially")
        return true
    }

    var index = 0
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        log("decodeElementIndex")
        return index++
    }
    @ExperimentalSerializationApi
    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? {
        log("decodeNullableSerializableValue")
        return null
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        log("decodeSerializableValue")
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
        log("decodeNullableSerializableElement $name")
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
        log("decodeSerializableElement $Naa")
        return deserializer.deserialize(this)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        log("beginStructure ${descriptor.serialName} $Naa ${descriptor.kind}")
        return CallTraceDecoder(descriptor, prefix + "\t", Naa)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        log("endStructure ${descriptor.serialName}")
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int {
        log("decodeCollectionSize $name")
        return when (name) {
            "mapOfStringToInt" -> 3
            "mutableMapOfStringToList" -> 4
            "arrayOfInt" -> 5
            else -> 2
        }
    }

    override fun decodeBoolean(): Boolean {
        log("decodeBoolean")
        return false
    }

    override fun decodeByte(): Byte {
        log("decodeByte")
        return 0
    }

    override fun decodeChar(): Char {
        log("decodeChar")
        return 'a'
    }

    override fun decodeDouble(): Double {
        log("decodeDouble")
        return 0.0
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        log("decodeEnum")
        return 0
    }

    override fun decodeFloat(): Float {
        log("decodeFloat")
        return 0.0f
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        log("decodeInline")
        return this
    }

    override fun decodeInt(): Int {
        log("decodeInt")
        return 0
    }

    override fun decodeLong(): Long {
        log("decodeLong")
        return 0L
    }

    @ExperimentalSerializationApi
    override fun decodeNotNullMark(): Boolean {
        log("decodeNotNullMark")
        return false
    }

    @ExperimentalSerializationApi
    override fun decodeNull(): Nothing? {
        log("decodeNull")
        return null
    }

    override fun decodeShort(): Short {
        log("decodeShort")
        return 0
    }

    override fun decodeString(): String {
        log("decodeString")
        return "booleanField"
    }

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
        val name = descriptor.getElementName(index)
        log("decodeBooleanElement $name")
        return false
    }

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
        val name = descriptor.getElementName(index)
        log("decodeByteElement $name")
        return 0
    }

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
        val name = descriptor.getElementName(index)
        log("decodeCharElement $name")
        return 'a'
    }

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
        val name = descriptor.getElementName(index)
        log("decodeDoubleElement $name")
        return 0.0
    }


    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
        val name = descriptor.getElementName(index)
        log("decodeFloatElement $name")
        return 0.0f
    }

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
        val name = descriptor.getElementName(index)
        log("decodeInlineElement $name")
        return this
    }

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
        val name = descriptor.getElementName(index)
        log("decodeIntElement $name")
        return 0
    }

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
        val name = descriptor.getElementName(index)
        log("decodeLongElement $name")
        return 0L
    }

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
        val name = descriptor.getElementName(index)
        log("decodeShortElement $name")
        return 0
    }

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
        val name = descriptor.getElementName(index)
        log("decodeStringElement $name")
        return ""
    }
}