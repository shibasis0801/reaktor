package dev.shibasis.reaktor.io.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

@OptIn(ExperimentalForeignApi::class)
actual object ClientPlatform {
    actual val os: String = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"
    actual val device: String = (NSProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] as? String)
        ?: memScoped { alloc<utsname>().also { uname(it.ptr) }.machine.toKString() }
}
