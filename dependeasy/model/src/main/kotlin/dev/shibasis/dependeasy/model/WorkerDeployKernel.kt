package dev.shibasis.dependeasy.model

import java.io.File
import java.net.HttpURLConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object WorkerDeployKernel {
    private val json get() = BuildModelReader.json

    fun plan(spec: WorkerDeploySpec, bundle: File, gitSha: String, runner: String,
        execute: (ProcessExecution) -> String): WorkerDeployPlan {
        val configFile = File(spec.execution.directory).resolve(spec.configuration)
        val configuration = json.parseToJsonElement(configFile.readText()).jsonObject
        require(configuration["name"]?.jsonPrimitive?.content == spec.service.name) { "Configuration does not name the declared service" }
        require(spec.execution.arguments.isEmpty()) { "Worker deploy arguments belong to Wrangler" }
        require(!bundle.exists() || bundle.listFiles().orEmpty().isEmpty()) { "Deploy plan requires an empty bundle directory" }
        val before = active(spec, execute)
        val version = json.parseToJsonElement(execute(Wrangler.version(spec, before))).jsonObject
        require(version.getValue("id").jsonPrimitive.content == before)
        execute(Wrangler.dryRun(spec, bundle.absolutePath))
        val entryName = File(configuration.getValue("main").jsonPrimitive.content).nameWithoutExtension + ".js"
        val entry = bundle.resolve(entryName)
        require(entry.isFile) { "Wrangler did not produce the declared entry bundle" }
        val resources = version.getValue("resources").jsonObject
        val desired = settings(configuration)
        val observed = settings(resources, remote = true)
        val differences = (desired.keys + observed.keys).sorted().mapNotNull { setting ->
            val wanted = desired[setting]?.let { BuildModelReader.digest(canonical(it).toString()) }
            val current = observed[setting]?.let { BuildModelReader.digest(canonical(it).toString()) }
            if (wanted == current) null else ConfigDifference(setting, wanted, current)
        }
        val compared = setOf("compatibility_date", "compatibility_flags", "vars", "services", "kv_namespaces", "r2_buckets", "hyperdrive", "d1_databases")
        val local = setOf("\$schema", "name", "main", "account_id", "build", "tsconfig", "minify", "no_bundle", "rules", "find_additional_modules")
        return WorkerDeployPlan(spec, BuildModelReader.digest(configFile), artifactDigest(bundle, configuration, configFile.parentFile),
            bundle.absolutePath, entry.absolutePath, before, differences, ((configuration.keys - compared - local) + resources["bindings"]?.jsonArray.orEmpty().mapNotNull {
                val binding = it.jsonObject
                val type = binding.getValue("type").jsonPrimitive.content
                if (type in comparedBindings || type == "secret_text") null else "remote-binding:${binding.getValue("name").jsonPrimitive.content}"
            }).sorted(), gitSha, runner)
    }

    fun publish(spec: WorkerDeploySpec, root: File, run: String, gitSha: String, runner: String,
        execute: (ProcessExecution) -> String, record: (DeployReceipt) -> Unit): DeployReceipt {
        val planned = plan(spec, root.resolve("${GradleProtocol.PlanDirectory}/deploy/bundles/$run"), gitSha, runner, execute)
        store(root, planned)
        observe(planned, "$run-plan", record)
        return apply(planned, run, execute, record)
    }

    fun apply(plan: WorkerDeployPlan, run: String, execute: (ProcessExecution) -> String,
        record: (DeployReceipt) -> Unit): DeployReceipt = change(plan, run, "apply", execute, record) {
        execute(Wrangler.apply(plan.spec, plan.entry, run))
        val deployed = active(plan.spec, execute)
        val version = json.parseToJsonElement(execute(Wrangler.version(plan.spec, deployed))).jsonObject
        require(version["annotations"]?.jsonObject?.get("workers/message")?.jsonPrimitive?.content == run) { "Active version does not carry this run's message" }
        deployed
    }

    fun rollback(plan: WorkerDeployPlan, latest: DeployReceipt, previous: DeployReceipt, run: String,
        execute: (ProcessExecution) -> String, record: (DeployReceipt) -> Unit): DeployReceipt {
        listOf(latest, previous).forEach {
            require(it.node == plan.spec.node && it.environment == plan.spec.environment && it.service == plan.spec.service &&
                it.result == ReceiptStatus.Succeeded && it.remoteVersion != null) { "Rollback requires successful receipts for this deployment" }
        }
        require(latest.finishedAtEpochMillis >= previous.finishedAtEpochMillis && latest.run != previous.run)
        require(plan.previousVersion == latest.remoteVersion && latest.rollbackTarget == previous.remoteVersion) { "Rollback receipts do not describe the active version and its predecessor" }
        return change(plan, run, "rollback", execute, record) {
            execute(Wrangler.rollback(plan.spec, requireNotNull(previous.remoteVersion), run))
            active(plan.spec, execute).also { require(it == previous.remoteVersion) { "Rollback version is not active" } }
        }
    }

    fun planFile(root: File, node: NodeId, environment: Environment) = root.resolve(
        "${GradleProtocol.PlanDirectory}/deploy/${BuildModelReader.digest("$node:${environment.name}")}.json")

    fun store(root: File, plan: WorkerDeployPlan) = write(planFile(root, plan.spec.node, plan.spec.environment), json.encodeToString(plan))

    fun retained(root: File, node: NodeId, environment: Environment): WorkerDeployPlan =
        json.decodeFromString(planFile(root, node, environment).readText())

    fun record(root: File, receipt: DeployReceipt) {
        require(receipt.run.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "Invalid deploy run identity" }
        write(root.resolve("${GradleProtocol.ReceiptDirectory}/deploy/${receipt.run}.json"), json.encodeToString(receipt))
    }

    fun receipts(root: File, node: NodeId): List<DeployReceipt> = root.resolve("${GradleProtocol.ReceiptDirectory}/deploy")
        .listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.map { json.decodeFromString<DeployReceipt>(it.readText()) }
        .filter { it.node == node }.sortedByDescending { it.finishedAtEpochMillis }

    fun observe(plan: WorkerDeployPlan, run: String, record: (DeployReceipt) -> Unit) {
        val time = System.currentTimeMillis()
        record(DeployReceipt(plan.spec.node, plan.spec.environment, plan.spec.service, run, "observe", plan.gitSha, plan.runner,
            BuildModelReader.digest(json.encodeToString(plan)), plan.artifactDigest, plan.previousVersion, null,
            ReceiptStatus.Succeeded, "observed", time, time))
    }

    private fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        val pending = java.nio.file.Files.createTempFile(file.parentFile.toPath(), file.name, ".pending")
        try {
            java.nio.file.Files.writeString(pending, text + "\n")
            java.nio.file.Files.move(pending, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { java.nio.file.Files.deleteIfExists(pending) }
    }

    fun verify(probe: HealthProbe): Int {
        val connection = java.net.URI(probe.url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    private fun change(plan: WorkerDeployPlan, run: String, action: String, execute: (ProcessExecution) -> String,
        record: (DeployReceipt) -> Unit, mutate: () -> String): DeployReceipt {
        require(run.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "Invalid deploy run identity" }
        val started = System.currentTimeMillis()
        var phase = "precondition"
        var remoteVersion: String? = null
        var health: Int? = null
        var failure: Throwable? = null
        try {
            val config = File(plan.spec.execution.directory).resolve(plan.spec.configuration)
            require(BuildModelReader.digest(config) == plan.configurationDigest) { "Configuration changed after planning" }
            require(artifactDigest(File(plan.bundle), json.parseToJsonElement(config.readText()).jsonObject, config.parentFile) == plan.artifactDigest) { "Bundle or assets changed after planning" }
            require(active(plan.spec, execute) == plan.previousVersion) { "Live version changed after planning" }
            phase = action
            remoteVersion = mutate()
            phase = "verify"
            plan.spec.health?.let { probe -> health = verify(probe); require(health == probe.status) { "Declared health probe failed" } }
            phase = "complete"
        } catch (error: Exception) { failure = error }
        val receipt = DeployReceipt(plan.spec.node, plan.spec.environment, plan.spec.service, run, action, plan.gitSha, plan.runner,
            BuildModelReader.digest(json.encodeToString(plan)), plan.artifactDigest, remoteVersion, plan.previousVersion,
            if (failure == null) ReceiptStatus.Succeeded else ReceiptStatus.Failed, phase, started, System.currentTimeMillis(), health,
            failure?.javaClass?.simpleName)
        record(receipt)
        failure?.let { throw it }
        return receipt
    }

    private fun active(spec: WorkerDeploySpec, execute: (ProcessExecution) -> String): String {
        val deployments = json.parseToJsonElement(execute(Wrangler.deployments(spec))).jsonArray
        val current = if (deployments.size == 1) deployments.single() else deployments.maxByOrNull {
            java.time.Instant.parse(it.jsonObject.getValue("created_on").jsonPrimitive.content)
        }
        val versions = current?.jsonObject?.get("versions")?.jsonArray
        require(versions?.size == 1 && versions.single().jsonObject.getValue("percentage").jsonPrimitive.double == 100.0) { "Deploy requires one observed version at 100% traffic" }
        return versions.single().jsonObject.getValue("version_id").jsonPrimitive.content.also {
            require(it.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid Cloudflare version identity" }
        }
    }

    private fun artifactDigest(bundle: File, config: JsonObject, directory: File): String {
        fun files(root: File) = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .map { it.relativeTo(root).invariantSeparatorsPath + ":" + BuildModelReader.digest(it) }.toList()
        val bundled = files(bundle)
        require(bundled.isNotEmpty()) { "Deploy bundle is empty" }
        val assets = config["assets"]?.jsonObject?.get("directory")?.jsonPrimitive?.content?.let {
            val root = directory.resolve(it); require(root.isDirectory) { "Declared assets are missing" }; files(root)
        }.orEmpty()
        return BuildModelReader.digest(json.encodeToString(listOf(bundled, assets)))
    }

    private val comparedBindings = setOf("plain_text", "json", "kv_namespace", "r2_bucket", "hyperdrive", "d1", "service")

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    private fun settings(value: JsonObject, remote: Boolean = false): Map<String, JsonElement> {
        val runtime = if (remote) value.getValue("script_runtime").jsonObject else value
        val settings = linkedMapOf<String, JsonElement>()
        listOf("compatibility_date", "compatibility_flags").forEach { key -> runtime[key]?.let { settings[key] = it } }
        settings.putIfAbsent("compatibility_flags", JsonArray(emptyList()))
        if (remote) {
            value["bindings"]?.jsonArray.orEmpty().forEach { item ->
                val binding = item.jsonObject
                val name = binding.getValue("name").jsonPrimitive.content
                if (binding.getValue("type").jsonPrimitive.content !in comparedBindings) return@forEach
                settings["binding:$name"] = JsonObject(binding.filterKeys { it !in setOf("name", "hidden") }.toSortedMap())
            }
        } else {
            value["vars"]?.jsonObject.orEmpty().forEach { (name, text) -> settings["binding:$name"] = buildJsonObject {
                if (text is JsonPrimitive && text.isString) { put("text", text); put("type", "plain_text") }
                else { put("json", text); put("type", "json") }
            } }
            mapOf("kv_namespaces" to "kv_namespace", "r2_buckets" to "r2_bucket", "hyperdrive" to "hyperdrive",
                "d1_databases" to "d1", "services" to "service").forEach { (key, type) ->
                value[key]?.jsonArray.orEmpty().forEach { item ->
                    val binding = item.jsonObject
                    val name = binding.getValue("binding").jsonPrimitive.content
                    settings["binding:$name"] = buildJsonObject {
                        val names = when (key) {
                            "d1_databases" -> mapOf("database_id" to "id")
                            "services" -> mapOf("service" to "service", "environment" to "environment", "entrypoint" to "entrypoint")
                            "r2_buckets" -> mapOf("bucket_name" to "bucket_name", "jurisdiction" to "jurisdiction")
                            else -> mapOf("id" to "id")
                        }
                        names.forEach { (source, target) -> binding[source]?.let { put(target, it) } }
                        put("type", type)
                    }
                }
            }
        }
        return settings
    }
}
