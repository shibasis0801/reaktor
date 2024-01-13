package dev.shibasis.reaktor.flatinvoker.networkCache

import kotlinx.serialization.Serializable

// generate random byte array
fun randomByteArray(size: Int): ByteArray {
    val byteArray = ByteArray(size)
    for (i in 0 until size) {
        byteArray[i] = (0..255).random().toByte()
    }
    return byteArray
}

val Data1 = randomByteArray(1_000_000)
val Data2 = randomByteArray(1_000_000)
val Data3 = randomByteArray(1_000_000)
val Data4 = randomByteArray(1_000_000)
val Data5 = randomByteArray(1_000_000)

@Serializable
data class HugeBenchmark(
    val id: Int,
    val data: ByteArray,
    val data1: ByteArray = Data1,
    val data2: ByteArray = Data2,
    val data3: ByteArray = Data3,
    val data4: ByteArray = Data4,
    val data5: ByteArray = Data5,
    val url: String,
    val ttl: Long,
    val timestamp: Long,
    val cacheKey: String
) {
    val size = data.size
}

@Serializable
data class NetworkCache(
    val id: Int,
    val data: ByteArray = byteArrayOf(),
    val url: String,
    val ttl: Long,
    val timestamp: Long,
    val cacheKey: String
) {
    val size = data.size
}
