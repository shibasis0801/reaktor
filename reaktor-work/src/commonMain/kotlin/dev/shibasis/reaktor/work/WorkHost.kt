package dev.shibasis.reaktor.work

/** Opens a minimal authorized host from persisted scope; never requires an Activity or DOM. */
fun interface WorkHost {
    suspend fun open(scope: WorkScope): WorkHostSession
}

class WorkHostSession(val runtime: WorkRuntime, private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
}
