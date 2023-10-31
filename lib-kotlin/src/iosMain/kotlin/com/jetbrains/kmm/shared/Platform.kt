package com.jetbrains.kmm.shared

import dev.shibasis.reaktor.native.reaktorTest
import dev.shibasis.reaktor.native.getName
import dev.shibasis.reaktor.native.getNameCpp
import kotlinx.cinterop.toKString
import platform.UIKit.UIDevice

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
}