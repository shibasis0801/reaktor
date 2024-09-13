package dev.shibasis.flatinvoker.ffi

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.Reference
import com.google.flatbuffers.kotlin.Vector
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import dev.shibasis.flatinvoker.ffi.payload.FlexPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface Invokable {
    fun invokeSync(payload: ByteArray): Long
    fun invokeAsync(payload: ByteArray): Flow<Long>
}

fun interface SyncInvokable {
    fun invokeSync(payload: FlexPayload): Long
}

fun interface AsyncInvokable {
    fun invokeAsync(payload: FlexPayload): Flow<Long>
}
