package com.jetbrains.kmm.shared.networkCache

data class NetworkCache(
    val id: Int,
    val data: ByteArray,
    val url: String,
    val ttl: Long,
    val timestamp: Long,
    val cacheKey: String
) {
    val size = data.size
}