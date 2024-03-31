@file:Suppress("KotlinJniMissingFunction")

package dev.shibasis.flatinvoker.ffi

import com.google.flatbuffers.kotlin.Vector
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import dev.shibasis.flatinvoker.ffi.payload.FlexPayload
import dev.shibasis.flatinvoker.ffi.payload.argument
import dev.shibasis.flatinvoker.ffi.payload.functionName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

object Tester: Invokable {
    init {
        System.loadLibrary("FlatInvokerFFI")
    }
    external fun test(): Int

    override fun invokeSync(payload: FlexPayload): Long {
        val fnName = payload.functionName

        val a = payload.argument<Int>(0)
        val b = payload.argument<Int>(1)

        val result = when(fnName) {
            "add" -> a + b
            "sub" -> a - b
            "mul" -> a * b
            "div" -> a / b
            else -> throw IllegalArgumentException("Function not found")
        }

        return encodeToFlexBuffer(result)
    }

    override fun invokeAsync(payload: FlexPayload): Flow<Long> {
        return flow { emit(-1) }
    }
}