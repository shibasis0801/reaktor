package dev.shibasis.reaktor.core.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData


@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    return this.bytes?.readBytes(this.length.toInt()) ?: ByteArray(0)
}

fun NSData.kotlinString() = toByteArray().decodeToString()

