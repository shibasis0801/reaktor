package dev.shibasis.reaktor.cloud

/** Owns clients, streams and tunnels even when cancellation races resource acquisition. */
class InfrastructureSession : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val resources = mutableListOf<AutoCloseable>()

    fun <T : AutoCloseable> own(resource: T): T {
        synchronized(lock) {
            if (!closed) {
                resources.add(resource)
                return resource
            }
        }
        runCatching { resource.close() }
        error("Infrastructure session is closed")
    }

    override fun close() {
        val releasing = synchronized(lock) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        releasing.reversed().forEach { runCatching { it.close() } }
    }
}
