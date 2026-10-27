package dev.shibasis.reaktor.core.framework

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.ArrayBufferView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

fun ByteArray.toUint8Array(): Uint8Array = unsafeCast<Int8Array>().let { Uint8Array(it.buffer, it.byteOffset, it.length) }

fun ArrayBuffer.toByteArray(): ByteArray = Int8Array(this).unsafeCast<ByteArray>()

fun ArrayBufferView.toByteArray(): ByteArray = Int8Array(buffer, byteOffset, byteLength).unsafeCast<ByteArray>()
