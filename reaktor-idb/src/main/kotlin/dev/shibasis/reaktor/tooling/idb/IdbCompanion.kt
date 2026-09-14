package dev.shibasis.reaktor.tooling.idb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Socket

/**
 * Starts and owns an `idb_companion` process for one target.
 *
 * One companion serves one UDID, so a workbench watching two devices runs two of them. They are
 * started on distinct ports rather than the companion's default, because the default is the one a
 * developer's own `idb` session will already be using.
 */
class IdbCompanion private constructor(
    private val process: Process,
    val port: Int,
    val udid: String,
) : AutoCloseable {

    fun client(): IdbCompanionClient = IdbCompanionClient(port = port)

    val alive: Boolean get() = process.isAlive

    override fun close() {
        process.destroy()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /** Where Homebrew puts it, plus PATH. Named explicitly so a refusal can say what to install. */
        fun locate(): String? {
            val candidates = buildList {
                System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
                    .forEach { add(File(it, "idb_companion")) }
                listOf("/opt/homebrew/bin", "/usr/local/bin").forEach { add(File(it, "idb_companion")) }
            }
            return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
        }

        /**
         * Launches a companion and waits for its port to accept connections.
         *
         * The wait is a TCP probe rather than a log scrape: the companion writes its readiness to
         * stderr in a format that has changed between versions, and a socket that accepts is the
         * only fact that actually matters.
         */
        suspend fun start(
            udid: String,
            port: Int = 0,
            readyTimeoutMillis: Long = 20_000,
        ): IdbCompanion {
            val binary = locate()
                ?: error("idb_companion is not installed. brew install idb-companion")
            val chosen = if (port != 0) port else freePort()
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(
                    binary,
                    "--udid", udid,
                    "--grpc-port", chosen.toString(),
                    "--log-level", "info",
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
            val ready = withTimeoutOrNull(readyTimeoutMillis) {
                while (true) {
                    if (!process.isAlive) return@withTimeoutOrNull false
                    if (accepts(chosen)) return@withTimeoutOrNull true
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (ready != true) {
                process.destroyForcibly()
                error("idb_companion did not start listening on port $chosen for $udid")
            }
            return IdbCompanion(process, chosen, udid)
        }

        private suspend fun accepts(port: Int): Boolean = withContext(Dispatchers.IO) {
            runCatching { Socket("127.0.0.1", port).close() }.isSuccess
        }

        private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
    }
}
