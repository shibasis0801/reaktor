package dev.shibasis.reaktor.io.network.websocket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class WebSocketOptions(
    val minUptime: Duration = 5.seconds,
    val connectionTimeout: Duration = 4.seconds,
    val maxEnqueuedMessages: Int = Int.MAX_VALUE,
    val startClosed: Boolean = false,
    val debug: Boolean = false,
    val logger: Logger? = null
)
