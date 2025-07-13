package dev.shibasis.reaktor.ffi.payload

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.Vector
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.reaktor.core.serialization.decodeFromFlexBuffer

typealias FlexPayload = Vector

/*
Wrapper classes will load the GC, extension functions/properties are better.

FFI protocol
 Field 0 -> moduleName
 Field 1 -> functionName
 Field 2 -> sequenceNumber
 ...more fields for network (will use grpc, so whatever stuff it needs)

 Field 3 + index -> actual arguments after 3 protocol fields


Protocol is simple so that it is easy to implement in any language.
Protocol will be used with ByteBuffers for in-process, grpc for network.

*/


fun ByteArray.toFlexPayload(): FlexPayload {
    val buffer = ArrayReadBuffer(this)
    val root = getRoot(buffer)
    return root.toVector()
}

inline val FlexPayload.moduleName: String
    get() = this[0].toString()

inline val FlexPayload.functionName: String
    get() = this[1].toString()

inline val FlexPayload.sequenceNumber: Long
    get() = this[2].toLong()

inline val FlexPayload.isFlow: Boolean
    get() = sequenceNumber != -1L

inline fun<reified T> FlexPayload.argument(idx: Int): T {
    val actualIdx = idx + 3
    val flexPointer = this[actualIdx].toInt().toLong()
    return decodeFromFlexBuffer<T>(flexPointer)
}
