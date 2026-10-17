package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
@ConsistentCopyVisibility
data class Run internal constructor(
    val host: HostRequirement = HostRequirement(),
    val timeoutMillis: Long? = null,
) {
    fun onLocal() = copy(host = host.copy(execution = ExecutionHost.Local))
    fun onWorker() = copy(host = host.copy(execution = ExecutionHost.Worker))

    fun timeout(duration: Duration): Run {
        require(duration.isFinite() && duration.isPositive()) { "A run timeout must be positive and finite" }
        return copy(timeoutMillis = duration.inWholeMilliseconds.also { require(it > 0) })
    }

    companion object {
        val Default = Run()
        fun onWorker() = Run(HostRequirement(execution = ExecutionHost.Worker))
        fun onLocal() = Run(HostRequirement(execution = ExecutionHost.Local))
        fun onPhones() = onLocal()
        fun onDesktopSession() = onLocal()
        fun onLinux() = Run(HostRequirement(HostPlatform.Linux))
        fun onMac() = Run(HostRequirement(HostPlatform.Macos))
        fun timeout(duration: Duration) = Default.timeout(duration)
    }
}
