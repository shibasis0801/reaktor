package dev.shibasis.flatinvoker.ffi.target

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class Struct(
    val number: Double,
    val text: String
)

interface Target {
    fun streamStructs(): Flow<Struct>
    fun getStruct(): Struct
}


