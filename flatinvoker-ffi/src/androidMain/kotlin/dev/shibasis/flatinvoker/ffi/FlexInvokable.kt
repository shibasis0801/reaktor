@file:Suppress("KotlinJniMissingFunction")

package dev.shibasis.flatinvoker.ffi

import com.google.flatbuffers.kotlin.ArrayReadBuffer
import com.google.flatbuffers.kotlin.getRoot
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

object Tester: Invokable {
    init {
        System.loadLibrary("FlatInvokerFFI")
    }
    external fun test(): Int
    fun getSync(payload: HashMap<String, String>) = payload.entries.joinToString {
        "${it.key}=${it.value}"
    }
    fun getFlow(payload: HashMap<String, String>) = flow {
        payload.entries.forEach {
            emit("${it.key}=${it.value}")
            delay(1000)
        }
    }

    override fun invokeSync(flexBuffer: ByteArray): Long {
        val root = getRoot(ArrayReadBuffer(flexBuffer)).toVector()
        val a = root[0].toInt()
        val b = root[1].toInt()

        return (a + b).toLong()

//        val fnName = root[0].toString()
//         need decoder here
//        val map = hashMapOf<String, String>()
//        root[1].toMap().entries.forEach {
//            map[it.key.toString()] = it.value.toString()
//        }

//        return when(fnName) {
//            "getSync" -> encodeToFlexBuffer(getSync(map))
//            else -> throw IllegalArgumentException("Function not found")
//        }
    }

    override fun invokeAsync(flexBuffer: ByteArray): Flow<Long> {
        val root = getRoot(ArrayReadBuffer(flexBuffer)).toVector()

        val fnName = root[0].toString()
        // need decoder here
        val map = hashMapOf<String, String>()
        root[1].toMap().entries.forEach {
            map[it.key.toString()] = it.value.toString()
        }

        return when(fnName) {
            "getFlow" -> getFlow(map).map { encodeToFlexBuffer(it) }
            else -> throw IllegalArgumentException("Function not found")
        }
    }
}