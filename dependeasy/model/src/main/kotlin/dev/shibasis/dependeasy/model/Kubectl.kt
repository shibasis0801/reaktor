package dev.shibasis.dependeasy.model

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

object Kubectl {
    fun deployment(spec: ServerRolloutSpec): JsonObject = BuildModelReader.json.parseToJsonElement(
        execute(spec, listOf("get", "deployment", spec.deployment, "-o", "json"))).jsonObject

    fun pods(spec: ServerRolloutSpec, selector: String): JsonArray = BuildModelReader.json.parseToJsonElement(
        execute(spec, listOf("get", "pods", "-l", selector, "-o", "json"))).jsonObject.getValue("items").jsonArray

    fun patch(spec: ServerRolloutSpec, patch: JsonArray) {
        execute(spec, listOf("patch", "deployment", spec.deployment, "--type=json", "-p", patch.toString(), "-o", "name"))
    }

    fun ready(spec: ServerRolloutSpec) {
        execute(spec, listOf("rollout", "status", "deployment/${spec.deployment}", "--timeout=180s"), rollout = true)
    }

    private fun execute(spec: ServerRolloutSpec, arguments: List<String>, rollout: Boolean = false): String {
        val directory = File(spec.execution.directory)
        val config = directory.resolve(spec.kubeconfig)
        require(config.isFile && config.canRead()) { "Declared Kubernetes access is unavailable" }
        val process = ProcessBuilder(listOf(spec.execution.executable, "--kubeconfig", config.absolutePath,
            "--request-timeout=${if (rollout) 190 else 20}s", "-n", spec.namespace) + arguments)
            .directory(directory).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var output = byteArrayOf()
        var readFailure: Exception? = null
        val reader = Thread({
            try {
                output = process.inputStream.use { it.readNBytes(1_048_577) }
                if (output.size > 1_048_576) process.destroyForcibly()
            } catch (error: Exception) { readFailure = error }
        }, "dependeasy-kubectl-output").apply { isDaemon = true; start() }
        try {
            require(process.waitFor(if (rollout) 190 else 25, TimeUnit.SECONDS)) { "Kubernetes command timed out" }
            reader.join(2_000)
            require(!reader.isAlive && readFailure == null && output.size <= 1_048_576) { "Kubernetes response exceeds its bounded read" }
            require(process.exitValue() == 0) { "Kubernetes command failed; provider output withheld" }
            return output.toString(Charsets.UTF_8)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }
}
