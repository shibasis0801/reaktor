package com.myntra.appscore.batcave.utilities

expect fun invokeGC()
expect fun timeMicroseconds(): Double
expect fun timeMilliseconds(): Double

expect fun timeEpoch(): Long

inline fun measureTime(block: () -> Any): Double {
    val start = timeMilliseconds()
    block()
    val end = timeMilliseconds()
    return (end - start)
}