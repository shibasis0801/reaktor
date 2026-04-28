package dev.shibasis.reaktor.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

@OptIn(ExperimentalForeignApi::class)
fun isIOSVersionAtLeast(major: Int, minor: Int = 0, patch: Int = 0): Boolean {
    val version = NSProcessInfo.processInfo.operatingSystemVersion
    return version.useContents {
        when {
            majorVersion.toInt() < major -> false
            majorVersion.toInt() > major -> true
            minorVersion.toInt() < minor -> false
            minorVersion.toInt() > minor -> true
            patchVersion.toInt() >= patch -> true
            else -> false
        }
    }
}
