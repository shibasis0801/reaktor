package dev.shibasis.flatinvoker.react

import platform.Foundation.*

actual fun getEpochTime(): Long {
    return NSDate.date().timeIntervalSince1970().toLong() * 1000
}