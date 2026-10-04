package dev.shibasis.reaktor.performance

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class MeasureResultsSerializer<T>(element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element).nullable
    override val descriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): List<T> = delegate.deserialize(decoder).orEmpty()
    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)
}
