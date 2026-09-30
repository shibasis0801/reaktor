package dev.shibasis.reaktor.devtools

import android.os.Process
import android.os.SystemClock

internal actual fun millisSinceProcessStart(): Double? =
    (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()).toDouble()
