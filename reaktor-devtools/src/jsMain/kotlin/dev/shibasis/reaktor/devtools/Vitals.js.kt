package dev.shibasis.reaktor.devtools

/**
 * A browser agent dials out rather than listening, and the vitals it would report come from
 * `PerformanceObserver` rather than from a platform frame callback. Until that attachment shape
 * is wired, the sources report nothing rather than approximating.
 */
private object WebFrameVitals : FrameVitalsSource {
    override fun start(onFrame: (frameNanos: Long, intervalNanos: Long) -> Unit): Cancellable? = null
}

private object WebMemoryVitals : MemoryVitalsSource {
    override fun read(): MemoryReading? = null
}

actual fun frameVitalsSource(): FrameVitalsSource = WebFrameVitals

actual fun memoryVitalsSource(): MemoryVitalsSource = WebMemoryVitals

actual fun crashStore(applicationId: String): CrashStore = object : CrashStore {
    override fun write(report: String) = Unit
    override fun readAll(): List<String> = emptyList()
    override fun clear() = Unit
}

actual fun installCrashHandler(agent: DevToolsAgent, store: CrashStore): Cancellable? = null
