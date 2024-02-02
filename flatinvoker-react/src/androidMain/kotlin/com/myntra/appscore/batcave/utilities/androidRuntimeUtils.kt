package com.myntra.appscore.batcave.utilities

actual fun timeMicroseconds() = System.nanoTime() / 1000.0
actual fun timeMilliseconds() =  System.nanoTime() / 1000000.0
actual fun timeEpoch() = System.currentTimeMillis()