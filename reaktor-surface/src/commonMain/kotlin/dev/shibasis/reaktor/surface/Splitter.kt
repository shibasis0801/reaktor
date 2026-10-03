package dev.shibasis.reaktor.surface

fun fitSize(size: Float, min: Float, max: Float): Float = size.coerceIn(min, max.coerceAtLeast(min))
