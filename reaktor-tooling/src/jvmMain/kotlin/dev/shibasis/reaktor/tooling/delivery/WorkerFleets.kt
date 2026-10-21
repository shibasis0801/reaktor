package dev.shibasis.reaktor.tooling.delivery

import dev.shibasis.dependeasy.model.WorkerDeployable
import dev.shibasis.dependeasy.model.WorkerFleetContract
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads only the Kotlin-declared fleet and its final configurations. */
object WorkerFleets {
    fun read(root: File, record: (File) -> File = { it }): WorkerFleetContract? {
        val file = record(File(root, "build/dependeasy/contracts/cloudflare/fleet.json"))
        if (!file.isFile) return null
        require(file.length() <= 2_000_000) { "Worker fleet too large" }
        return Json.decodeFromString<WorkerFleetContract>(file.readText()).also { contract ->
            require(contract.schema == 1 && contract.defaultEnvironment != null)
            require(contract.workers.map { it.name }.distinct().size == contract.workers.size)
            require(contract.workers.map { it.node }.distinct().size == contract.workers.size)
        }
    }

    fun configuration(root: File, worker: WorkerDeployable, environment: String,
        record: (File) -> File = { it }): Pair<File, JsonObject> {
        val directory = File(root, worker.directory).canonicalFile
        val file = record(File(root, worker.configurations.getValue(environment)).canonicalFile)
        require(directory.toPath().startsWith(root.canonicalFile.toPath()) && file.toPath().startsWith(directory.toPath()))
        require(file.isFile && file.length() <= 2_000_000) { "Declared worker configuration unavailable" }
        val config = Json.parseToJsonElement(file.readText()).jsonObject
        require(config["name"]?.jsonPrimitive?.content == worker.services.getValue(environment))
        return file to config
    }
}
