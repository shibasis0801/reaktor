package dev.shibasis.reaktor.security

internal fun encodeRosterIndices(indices: List<Int>): ByteArray {
    require(indices.size <= 4095 && indices.all { it >= 0 } && indices.toSet().size == indices.size)
    return ByteArray(indices.size * 4) { position ->
        (indices[position / 4] ushr (24 - (position % 4) * 8)).toByte()
    }
}
