package dev.shibasis.reaktor.experiments.cloudflarehello.files

import dev.shibasis.reaktor.cloudflare.file
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.Service
import dev.shibasis.reaktor.io.adapters.FileAdapter
import dev.shibasis.reaktor.io.adapters.WebFileAdapter
import kotlin.js.Date

class FileService : Service() {
    init {
        GetHandler<FileProbeRequest, FileProbeResponse>("/runtime") {
            probe(
                storage = "worker-runtime-filesystem",
                durable = false,
                note = "Cloudflare Worker filesystem is runtime-local scratch space. It may not persist across requests.",
                file = WebFileAdapter(
                    cacheDirectory = "worker-cache",
                    documentDirectory = "worker-documents",
                ),
            )
        }

        GetHandler<FileProbeRequest, FileProbeResponse>("/r2") { request ->
            probe(
                storage = "cloudflare-r2",
                durable = true,
                note = "R2 is durable object storage and should persist across requests and deployments.",
                file = request.file(
                    FileBindings.media,
                    cacheDirectory = "cache",
                    documentDirectory = "documents",
                ),
            )
        }
    }

    private suspend fun probe(
        storage: String,
        durable: Boolean,
        note: String,
        file: FileAdapter<*>,
    ): FileProbeResponse {
        val key = file.resolvePath("probe.txt")
        return runCatching {
            val previous = file.readTextFile(key)
            val written = "$storage:${Date().toISOString()}"
            file.writeTextFile(key, written)
            val readBack = file.readTextFile(key)
            FileProbeResponse(
                storage = storage,
                durable = durable,
                key = key,
                previous = previous,
                written = written,
                readBack = readBack,
                existsAfterWrite = file.exists(key),
                matches = written == readBack,
                note = note,
            )
        }.getOrElse { error ->
            FileProbeResponse(
                storage = storage,
                durable = durable,
                key = key,
                note = note,
                error = error.message ?: error.toString(),
            )
        }
    }
}
