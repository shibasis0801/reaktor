package dev.shibasis.flatinvoker.ffi.payload

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.Vector
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.serialization.decodeFromFlexBuffer


// Wrapper classes will load the GC, extension functions/properties are better.
// As long as this informal interface is maintained, we get best of both (performance / code quality)
typealias FlexPayload = Vector
fun ByteArray.toFlexPayload(): FlexPayload {
    val buffer = ArrayReadBuffer(this)
    return getRoot(buffer).toVector()
}

inline val FlexPayload.moduleName: String
    get() = this[0].toString()

inline val FlexPayload.functionName: String
    get() = this[1].toString()

inline fun<reified T> FlexPayload.argument(idx: Int): T {
    val actualIdx = idx + 2
    val flexPointer = this[actualIdx].toInt().toLong()
    return decodeFromFlexBuffer<T>(flexPointer)
}
