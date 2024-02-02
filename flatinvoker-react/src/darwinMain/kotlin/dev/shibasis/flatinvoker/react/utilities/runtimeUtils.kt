package dev.shibasis.flatinvoker.react.utilities

import platform.Foundation.NSDate
import platform.Foundation.date
import platform.Foundation.timeIntervalSince1970
import platform.QuartzCore.CACurrentMediaTime

// https://developer.apple.com/documentation/quartzcore/1395996-cacurrentmediatime
actual fun timeMicroseconds() = CACurrentMediaTime() * 1000 * 1000
actual fun timeMilliseconds() = CACurrentMediaTime() * 1000

actual fun timeEpoch() = NSDate.date().timeIntervalSince1970().toLong() * 1000

actual fun invokeGC() {
    kotlin.native.internal.GC.collect();
}