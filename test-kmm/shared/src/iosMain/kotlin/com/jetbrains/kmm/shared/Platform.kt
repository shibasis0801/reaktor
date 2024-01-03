package dev.shibasis.reaktor.flatinvoker

import dev.shibasis.reaktor.native.reaktorTest
import platform.UIKit.UIDevice

actual class Platform actual constructor() {
    actual val platform: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    actual val data: Int = reaktorTest()
}