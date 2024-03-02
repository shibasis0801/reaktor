package dev.shibasis.flatinvoker.core

import com.google.flatbuffers.kotlin.Map
import com.google.flatbuffers.kotlin.Vector

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow


interface Network {
    fun getResponse(url: String): Flow<String>
    fun getCachedResponse(url: String): String
    operator fun invoke(methodName: String, arguments: Vector): Any
    fun invokeAsync(methodName: String, arguments: Vector): Flow<Any>
    fun exposedMethods() = mapOf(
        "getResponse" to "async",
        "getCachedResponse" to "sync"
    )
}

object NetworkImpl: Network {
    override fun getResponse(url: String): Flow<String> {
        TODO("Not yet implemented")
    }

    override fun getCachedResponse(url: String): String {
        TODO("Not yet implemented")
    }

    override fun invoke(methodName: String, arguments: Vector): Any {
        when(methodName) {
            "getCachedResponse" -> {
                val url = arguments[0].toString()
                return getCachedResponse(url)
            }
            else -> throw Exception("Method $methodName not found")
        }
    }

    override fun invokeAsync(methodName: String, arguments: Vector): Flow<Any> {
        when(methodName) {
            "getResponse" -> {
                val url = arguments[0].toString()
                return getResponse(url)
            }
            else -> throw Exception("Method $methodName not found")
        }
    }
}