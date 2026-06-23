package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Runs an external CLI as a supervised subprocess and maps its stdout/stderr lines to
 * [CloudEvent]s. This is the JVM-native tool-execution boundary from the design: no embedded Node
 * service — the JVM just supervises a process (pulumi / dagger / wrangler) and parses a stream.
 */
class ProcessToolRunner {
    fun run(command: List<String>, workingDir: File? = null, runId: String): Flow<CloudEvent> = flow {
        emit(CloudEvent.Progress(runId, "starting: ${command.joinToString(" ")}"))
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { builder -> workingDir?.let { builder.directory(it) } }
            .start()
        process.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                emit(CloudEvent.Log(runId, line))
                line = reader.readLine()
            }
        }
        emit(CloudEvent.Completed(runId, process.waitFor()))
    }.flowOn(Dispatchers.IO)
}
