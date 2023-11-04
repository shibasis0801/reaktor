package com.jetbrains.kmm.shared

import dev.shibasis.reaktor.native.getName
import dev.shibasis.reaktor.native.getNameCpp
import dev.shibasis.reaktor.native.reaktorTest
import dev.shibasis.reaktor.native.sendByteArray
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString

object KotlinCpp {
    fun getIntFromC(): Int {
        return reaktorTest()
    }
    fun getNameFromC(): String? {
        return getName()?.toKString()
    }

    fun getNameFromCpp(): String? {
        return getNameCpp()?.toKString()
    }

    fun testSendBytesArray(byteArray: ByteArray): Boolean {
        val result = sendByteArray(byteArray.toUByteArray().toCValues(), byteArray.size)
        return result == 1
    }
}
