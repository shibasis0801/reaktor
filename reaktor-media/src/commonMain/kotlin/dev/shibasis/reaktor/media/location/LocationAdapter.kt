package dev.shibasis.reaktor.media.location

import dev.shibasis.reaktor.core.framework.Adapter
import io.ktor.utils.io.core.ByteOrder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class LocationAdapter: Adapter<Unit>(Unit) {

}