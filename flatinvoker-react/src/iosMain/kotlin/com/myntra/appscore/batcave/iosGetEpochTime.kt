package com.myntra.appscore.batcave

import platform.Foundation.*

actual fun getEpochTime(): Long {
    return NSDate.date().timeIntervalSince1970().toLong() * 1000
}