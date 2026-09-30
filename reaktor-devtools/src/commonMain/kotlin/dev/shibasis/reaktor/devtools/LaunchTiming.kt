package dev.shibasis.reaktor.devtools

import kotlinx.atomicfu.atomic

internal expect fun millisSinceProcessStart(): Double?

internal object LaunchTiming {
    private val reported = atomic(false)

    fun firstFrame(agent: DevToolsAgent) {
        if (!reported.compareAndSet(expect = false, update = true)) return
        val millis = millisSinceProcessStart()?.takeIf { it in 0.0..ColdLaunchLimitMillis } ?: return
        agent.startup.emit { sequence, nanos -> AgentFact.Startup(sequence, nanos, millis, "cold") }
    }
}

private const val ColdLaunchLimitMillis = 60_000.0
