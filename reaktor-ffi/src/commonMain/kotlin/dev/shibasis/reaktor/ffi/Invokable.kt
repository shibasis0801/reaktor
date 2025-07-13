package dev.shibasis.reaktor.ffi

import dev.shibasis.reaktor.ffi.payload.FlexPayload
import kotlinx.coroutines.flow.Flow

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
