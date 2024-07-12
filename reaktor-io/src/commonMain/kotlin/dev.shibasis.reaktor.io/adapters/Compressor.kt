package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.CreateSlot

data class CompressionResponse(
    val base64EncodedString: String,
)

data class CompressionRequest(
    val data: String
)

data class DecompressionRequest(
    val base64EncodedString: String
)

data class DecompressionResponse(
    val data: String
)


abstract class CompressionAdapter<Controller>(controller: Controller): Adapter<Controller>(controller) {
    abstract fun compress(request: CompressionRequest): CompressionResponse?
    abstract fun decompress(request: DecompressionRequest): DecompressionResponse?
}

var Feature.Compression by CreateSlot<CompressionAdapter<*>>()