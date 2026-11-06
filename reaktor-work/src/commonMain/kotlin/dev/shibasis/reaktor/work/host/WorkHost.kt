package dev.shibasis.reaktor.work.host

import dev.shibasis.reaktor.work.Work
import dev.shibasis.reaktor.work.execution.Drain
import dev.shibasis.reaktor.work.run.WorkScope

fun interface WorkHost {
    suspend fun open(scope: WorkScope): WorkHostSession
}

class WorkHostSession(val work: Work, private val release: () -> Unit = {}) : AutoCloseable {
    override fun close() = release()
}

suspend fun WorkHost.drain(scope: WorkScope): Drain {
    val session = open(scope)
    return try { session.work.drain() } finally { session.close() }
}
