package dev.shibasis.flatinvoker.core.serialization.util

import com.google.flatbuffers.kotlin.Reference

enum class TypeCategory {
    INVALID,
    PRIMITIVE,
    KEY,
    STRING,
    INDIRECT_PRIMITIVE,
    MAP,
    VECTOR,
    TYPED_VECTOR,
    BLOB
}

/* Needs updates if parent library changes */
fun Reference.typeCategory(): TypeCategory = when (type.value) {
    in 0..3, 26 -> TypeCategory.PRIMITIVE
    4 -> TypeCategory.KEY
    5 -> TypeCategory.STRING
    in 6..8 -> TypeCategory.INDIRECT_PRIMITIVE
    9 -> TypeCategory.MAP
    10 -> TypeCategory.VECTOR
    25 -> TypeCategory.BLOB
    in 11..24, 36 -> TypeCategory.TYPED_VECTOR
    else -> TypeCategory.INVALID
}