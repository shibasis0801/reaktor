package dev.shibasis.reaktor.devtools

import java.lang.management.ManagementFactory

internal actual fun millisSinceProcessStart(): Double? =
    runCatching { ManagementFactory.getRuntimeMXBean().uptime.toDouble() }.getOrNull()
