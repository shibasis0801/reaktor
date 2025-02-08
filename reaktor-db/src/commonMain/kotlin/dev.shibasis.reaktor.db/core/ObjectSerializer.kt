package dev.shibasis.reaktor.db.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

sealed interface ObjectSerializer<Output> {
    val serializersModule: SerializersModule
    fun<Input> serialize(serializer: KSerializer<Input>, value: Input): Output
    fun<Input> deserialize(serializer: KSerializer<Input>, data: Output): Input

    fun<Result> choose(text: Result, binary: Result): Result {
        return when(this) {
            is TextSerializer -> text
            is BinarySerializer -> binary
        }
    }

}

open class TextSerializer(private val json: Json = Json): ObjectSerializer<String> {
    override val serializersModule: SerializersModule
        get() = json.serializersModule

    override fun <T> serialize(serializer: KSerializer<T>, value: T): String {
        return json.encodeToString(serializer, value)
    }

    override fun <T> deserialize(serializer: KSerializer<T>, data: String): T {
        return json.decodeFromString(serializer, data)
    }
}

open class BinarySerializer: ObjectSerializer<ByteArray> {
    override val serializersModule: SerializersModule
        get() = TODO("Not yet implemented")


    override fun <Input> serialize(serializer: KSerializer<Input>, value: Input): ByteArray {
        TODO("Not yet implemented")
    }

    override fun <Input> deserialize(serializer: KSerializer<Input>, data: ByteArray): Input {
        TODO("Not yet implemented")
    }
}


class FlexSerializer: BinarySerializer() {
    override val serializersModule: SerializersModule
        get() = super.serializersModule

    override fun <Input> serialize(serializer: KSerializer<Input>, value: Input): ByteArray {
        return super.serialize(serializer, value)
    }

    override fun <Input> deserialize(serializer: KSerializer<Input>, data: ByteArray): Input {
        return super.deserialize(serializer, data)
    }
}