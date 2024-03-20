package dev.shibasis.flatinvoker.ffi

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.Vector
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.FlexBuffer
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface Invokable {
    fun invokeSync(flexBuffer: ByteArray): Long
    fun invokeAsync(flexBuffer: ByteArray): Flow<Long>
}

