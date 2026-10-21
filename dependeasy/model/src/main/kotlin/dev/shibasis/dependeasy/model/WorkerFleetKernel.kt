package dev.shibasis.dependeasy.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object WorkerFleetKernel {
    fun plan(spec: KernelSpec.WorkerFleet): WorkerFleetContract {
        require(spec.workers.map { it.ref.name }.distinct().size == spec.workers.size) { "Duplicate fleet worker" }
        val byRef = spec.workers.associateBy { it.ref }
        spec.defaults.paths.forEach(::relative)
        spec.contract.paths.forEach(::relative)
        require(spec.defaults.paths.size == 1 && spec.contract.paths.size == 1)
        val records = spec.workers.map { worker ->
            relative(worker.ref.directory)
            require(worker.deployments.isNotEmpty()) { "${worker.ref.name}: declare deployment environments" }
            require(worker.deployments.map { it.environment }.distinct().size == worker.deployments.size) { "${worker.ref.name}: duplicate environment" }
            require(worker.deployments.any { it.environment == spec.defaultEnvironment }) { "${worker.ref.name}: missing default environment" }
            worker.deployments.forEach { deployment ->
                require(deployment.service.name.isNotBlank()) { "${worker.ref.name}: empty service name" }
                require(deployment.secrets.distinct().size == deployment.secrets.size && deployment.secrets.all {
                    Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it)
                }) { "${worker.ref.name}: invalid or duplicate secret name" }
                require(deployment.serviceClient == null || deployment.serviceClient.isNotBlank()) { "${worker.ref.name}: empty service client" }
                deployment.endpoint?.let { endpoint ->
                    val uri = java.net.URI(endpoint)
                    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "${worker.ref.name}: invalid endpoint" }
                }
                require(deployment.bindings.map { it.binding }.distinct().size == deployment.bindings.size) { "${worker.ref.name}: duplicate service binding" }
                deployment.bindings.forEach { binding ->
                    require(binding.binding.isNotBlank())
                    if (binding is WorkerServiceBinding.Worker) {
                        val target = requireNotNull(byRef[binding.target]) { "Undeclared service worker: ${binding.target.name}" }
                        require(target.deployments.any { it.environment == deployment.environment }) { "${binding.target.name}: no ${deployment.environment.name} deployment" }
                    }
                }
            }
            WorkerDeployable(worker.ref.name, worker.node, worker.ref.directory,
                worker.deployments.associate { it.environment.name to "${worker.ref.directory}/wrangler.dependeasy-${it.environment.name}.json" },
                worker.deployments.associate { it.environment.name to it.service.name }, worker.deployments)
        }
        return WorkerFleetContract(workers = records, defaultEnvironment = spec.defaultEnvironment.name)
    }

    fun configurationSources(spec: KernelSpec.WorkerFleet): List<String> = spec.workers.flatMap { worker ->
        worker.deployments.map { deployment -> "${worker.ref.directory}/" +
            if (deployment.environment == spec.defaultEnvironment) "wrangler.json" else "wrangler.${deployment.environment.name}.json" }
    }

    fun bindings(spec: KernelSpec.WorkerFleet, worker: FleetWorker, environment: Environment): List<Pair<WorkerServiceBinding, ServiceRef>> {
        val byRef = spec.workers.associateBy { it.ref }
        return worker.deployments.single { it.environment == environment }.bindings.map { binding ->
            binding to when (binding) {
                is WorkerServiceBinding.Worker -> byRef.getValue(binding.target).deployments.single { it.environment == environment }.service
                is WorkerServiceBinding.External -> binding.target
            }
        }
    }

    fun render(spec: KernelSpec.WorkerFleet, defaults: String, configurations: Map<String, String>): Map<String, String> {
        val fleet = plan(spec)
        val base = Json.parseToJsonElement(defaults).jsonObject
        val outputs = linkedMapOf<String, String>()
        spec.workers.zip(fleet.workers).forEach { (worker, record) ->
            worker.deployments.forEach { deployment ->
                val environment = deployment.environment
                val source = "${worker.ref.directory}/" + if (environment == spec.defaultEnvironment) "wrangler.json" else "wrangler.${environment.name}.json"
                val local = Json.parseToJsonElement(configurations.getValue(source)).jsonObject
                check(local["name"]?.jsonPrimitive?.content == deployment.service.name) { "$source: name differs from the Kotlin deployment declaration" }
                val expected = JsonArray(bindings(spec, worker, environment).map { (binding, service) -> buildJsonObject {
                    put("binding", binding.binding); put("service", service.name)
                    binding.entrypoint?.let { put("entrypoint", it) }
                } })
                val config = merge(base, local)
                check(config["services"]?.jsonArray.orEmpty() == expected.toList()) { "$source: service bindings differ from the Kotlin deployment declaration" }
                outputs[record.configurations.getValue(environment.name)] = BuildModelReader.json.encodeToString(config) + "\n"
            }
        }
        outputs[spec.contract.paths.single()] = BuildModelReader.json.encodeToString(fleet) + "\n"
        return outputs
    }

    private fun merge(defaults: JsonObject, local: JsonObject): JsonObject = JsonObject(defaults + local.mapValues { (key, value) ->
        if (value is JsonObject) merge(defaults[key] as? JsonObject ?: JsonObject(emptyMap()), value) else value
    })

    private fun relative(path: String) {
        require(path.isNotBlank() && !java.io.File(path).isAbsolute && path.split('/', '\\').none { it == ".." }) { "Fleet path must remain inside the build: $path" }
    }
}
