package com.jetbrains.kmm.shared

import dev.shibasis.reaktor.native.reaktorTest
import dev.shibasis.reaktor.native.getName
import dev.shibasis.reaktor.native.getNameCpp
import dev.shibasis.reaktor.native.sendByteArray
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.UIKit.UIDevice
import platform.posix.u_int8_tVar
import platform.posix.uint8_tVar

actual class Platform actual constructor() {
    actual val platform: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    actual val data: Int = reaktorTest()
}

object FlatInvoker {
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
