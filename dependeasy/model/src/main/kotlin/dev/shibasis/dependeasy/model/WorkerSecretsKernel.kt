package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object WorkerSecretsKernel {
    fun plan(spec: WorkerSecretsSpec, gitSha: String, runner: String,
        execute: (ProcessExecution, String?) -> String): WorkerSecretsPlan {
        val names = observed(spec, execute)
        return WorkerSecretsPlan(spec, BuildModelReader.digest(File(spec.deployment.execution.directory).resolve(spec.deployment.configuration)), names,
            (spec.names - names.toSet()).sorted(), (names - spec.names.toSet()).sorted(), gitSha, runner)
    }

    fun apply(plan: WorkerSecretsPlan, root: File, run: String,
        execute: (ProcessExecution, String?) -> String, record: (DeployReceipt) -> Unit): DeployReceipt {
        val started = System.currentTimeMillis()
        val spec = plan.spec
        val deployment = spec.deployment
        val digest = BuildModelReader.digest(BuildModelReader.json.encodeToString(plan))
        fun receipt(status: ReceiptStatus, phase: String) = DeployReceipt(deployment.node, deployment.environment,
            deployment.service, run, "secrets-apply", plan.gitSha, plan.runner, digest, plan.configurationDigest,
            null, null, status, phase, started, System.currentTimeMillis(), failure =
                if (status == ReceiptStatus.Failed) "Worker secret update failed; tool output withheld" else null)
        try {
            require(BuildModelReader.digest(File(deployment.execution.directory).resolve(deployment.configuration)) == plan.configurationDigest)
            require(observed(spec, execute) == plan.observedNames)
            require(plan.missing == (spec.names - plan.observedNames.toSet()).sorted())
            require(plan.extra == (plan.observedNames - spec.names.toSet()).sorted())
            val values = values(spec, root)
            val retired = spec.retiredNames.intersect(plan.observedNames.toSet())
            if (values.isNotEmpty() || retired.isNotEmpty()) {
                val payload = buildJsonObject {
                    values.forEach { (name, value) -> put(name, value) }
                    retired.forEach { put(it, JsonNull) }
                }.toString()
                execute(Wrangler.putSecrets(deployment), payload)
            }
            val updated = observed(spec, execute)
            require(updated.containsAll(spec.names) && updated.none { it in spec.retiredNames })
        } catch (_: Exception) {
            record(receipt(ReceiptStatus.Failed, "secrets"))
            error("Worker secret update failed; tool output withheld")
        }
        return receipt(ReceiptStatus.Succeeded, "verified").also(record)
    }

    fun observe(plan: WorkerSecretsPlan, run: String, record: (DeployReceipt) -> Unit): DeployReceipt {
        val now = System.currentTimeMillis()
        val deployment = plan.spec.deployment
        return DeployReceipt(deployment.node, deployment.environment, deployment.service, run, "secrets-plan",
            plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            plan.configurationDigest, null, null, ReceiptStatus.Succeeded, "names-observed", now, now).also(record)
    }

    fun planFile(root: File, node: NodeId, environment: Environment): File =
        root.resolve("${GradleProtocol.PlanDirectory}/secrets/" + BuildModelReader.digest("$node:${environment.name}") + ".json")

    fun retained(root: File, node: NodeId, environment: Environment): WorkerSecretsPlan =
        BuildModelReader.json.decodeFromString(planFile(root, node, environment).readText())

    fun store(root: File, plan: WorkerSecretsPlan) {
        val deployment = plan.spec.deployment
        val file = planFile(root, deployment.node, deployment.environment)
        file.parentFile.mkdirs()
        val temporary = File.createTempFile(".secrets-plan-", ".json", file.parentFile)
        try {
            temporary.writeText(BuildModelReader.json.encodeToString(plan))
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    private fun observed(spec: WorkerSecretsSpec, execute: (ProcessExecution, String?) -> String): List<String> = try {
        val output = execute(Wrangler.secrets(spec.deployment), null)
        val names = BuildModelReader.json.parseToJsonElement(output).jsonArray.map { item ->
            val name = item.jsonObject.getValue("name").jsonPrimitive.content
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            name
        }
        require(names.distinct().size == names.size)
        names.sorted()
    } catch (_: Exception) { error("Cannot read Worker secret names; tool output withheld") }

    private fun values(spec: WorkerSecretsSpec, root: File): Map<String, String> {
        val directory = root.resolve("config").canonicalFile.toPath()
        val values = linkedMapOf<String, String>()
        spec.sources.forEach { source ->
            val file = root.resolve(source).canonicalFile
            require(file.toPath().startsWith(directory) && file.isFile)
            val entries = if (file.extension == "json") {
                BuildModelReader.json.parseToJsonElement(file.readText()).jsonObject.filterKeys { it in spec.names }.mapValues { it.value.jsonPrimitive.content }
            } else {
                file.readLines().mapNotNull { line ->
                    Regex("^(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$").matchEntire(line)?.let { match ->
                        val text = match.groupValues[2].trim()
                        val value = when {
                            text.startsWith("'") && text.endsWith("'") -> text.substring(1, text.length - 1).replace("'\\''", "'")
                            text.startsWith('"') && text.endsWith('"') -> text.substring(1, text.length - 1).replace("\\\"", "\"")
                            else -> text
                        }
                        match.groupValues[1] to value
                    }
                }.toMap()
            }
            entries.filterKeys { it in spec.names }.forEach { (name, value) ->
                require(value.isNotEmpty() && (values[name] == null || values[name] == value))
                values[name] = value
            }
        }
        require(values.keys.containsAll(spec.names))
        return values.toSortedMap()
    }
}
