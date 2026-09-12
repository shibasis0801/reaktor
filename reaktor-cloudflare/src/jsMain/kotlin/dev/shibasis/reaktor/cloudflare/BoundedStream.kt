package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise

internal suspend fun readBoundedStream(stream: dynamic, maxBytes: Int): ByteArray {
    require(maxBytes in 1..8_388_608)
    val reader = stream?.getReader() ?: return ByteArray(0)
    val chunks = mutableListOf<ByteArray>()
    var size = 0
    try {
        while (true) {
            val next = reader.read().unsafeCast<Promise<dynamic>>().await()
            if (next.done == true) break
            val length = (next.value.byteLength as Number).toInt()
            require(length <= maxBytes - size) { "Stream exceeds its preview limit" }
            size += length
            chunks += ByteArray(length) { (next.value[it] as Number).toByte() }
        }
    } finally {
        try { reader.cancel().unsafeCast<Promise<dynamic>>().await() } finally { reader.releaseLock() }
    }
    val bytes = ByteArray(size)
    var offset = 0
    chunks.forEach { it.copyInto(bytes, offset); offset += it.size }
    return bytes
}
