package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object ServerRolloutKernel {
    private val json get() = BuildModelReader.json

    fun store(root: File, plan: ServerRolloutPlan) {
        val file = WorkerDeployKernel.planFile(root, plan.spec.node, plan.spec.environment)
        file.parentFile.mkdirs()
        val pending = java.nio.file.Files.createTempFile(file.parentFile.toPath(), file.name, ".pending")
        try {
            java.nio.file.Files.writeString(pending, json.encodeToString(plan) + "\n")
            java.nio.file.Files.move(pending, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { java.nio.file.Files.deleteIfExists(pending) }
    }

    fun retained(root: File, node: NodeId, environment: Environment): ServerRolloutPlan =
        json.decodeFromString<ServerRolloutPlan>(WorkerDeployKernel.planFile(root, node, environment).readText()).also {
            require(it.spec.node == node && it.spec.environment == environment) { "Retained server plan has a different identity" }
        }

    fun manifest(spec: ServerRolloutSpec, image: String): JsonObject {
        spec.digest(image)
        val manifest = requireNotNull(spec.manifest) { "No declared server manifest" }
        require(manifest.getValue("apiVersion").jsonPrimitive.content == "apps/v1" &&
            manifest.getValue("kind").jsonPrimitive.content == "Deployment")
        val metadata = manifest.getValue("metadata").jsonObject
        require(metadata.getValue("name").jsonPrimitive.content == spec.deployment &&
            metadata.getValue("namespace").jsonPrimitive.content == spec.namespace) { "Manifest has a different deployment identity" }
        val desired = manifest.getValue("spec").jsonObject
        val rolling = desired.getValue("strategy").jsonObject
        val capacity = rolling.getValue("rollingUpdate").jsonObject
        require(desired.getValue("replicas").jsonPrimitive.int == 1 && rolling.getValue("type").jsonPrimitive.content == "RollingUpdate" &&
            capacity.getValue("maxSurge").jsonPrimitive.int == 0 && capacity.getValue("maxUnavailable").jsonPrimitive.int == 1)
        val template = desired.getValue("template").jsonObject
        val pod = template.getValue("spec").jsonObject
        val containers = pod.getValue("containers").jsonArray
        val selected = containers.indices.single { containers[it].jsonObject.getValue("name").jsonPrimitive.content == spec.container }
        val rendered = JsonArray(containers.mapIndexed { index, container ->
            if (index == selected) JsonObject(container.jsonObject + ("image" to JsonPrimitive(image))) else container
        })
        return JsonObject(manifest + ("spec" to JsonObject(desired +
            ("template" to JsonObject(template + ("spec" to JsonObject(pod + ("containers" to rendered))))))))
    }

    fun plan(spec: ServerRolloutSpec, image: String, gitSha: String, runner: String): ServerRolloutPlan {
        spec.digest(image)
        if (spec.manifest != null) manifest(spec, image)
        val current = deployment(spec)
        val previous = attested(spec, current)
        require(previous != image) { "Wanted digest is already running" }
        return ServerRolloutPlan(spec, image, previous, current.image, current.uid,
            current.value.getValue("metadata").jsonObject.getValue("resourceVersion").jsonPrimitive.content,
            current.index, gitSha, runner)
    }

    fun observe(plan: ServerRolloutPlan, run: String, record: (DeployReceipt) -> Unit) {
        val time = System.currentTimeMillis()
        record(DeployReceipt(plan.spec.node, plan.spec.environment, plan.spec.service, run, "observe", plan.gitSha, plan.runner,
            BuildModelReader.digest(json.encodeToString(plan)), plan.image.substringAfter('@'), plan.previousDigest, null,
            ReceiptStatus.Succeeded, "observed", time, time))
    }

    fun apply(plan: ServerRolloutPlan, run: String, record: (DeployReceipt) -> Unit): DeployReceipt =
        change(plan, run, "apply", record) {
            val current = deployment(plan.spec)
            require(current.uid == plan.uid && current.image == plan.previousImage && current.index == plan.containerIndex &&
                current.value.getValue("metadata").jsonObject.getValue("resourceVersion").jsonPrimitive.content == plan.resourceVersion) {
                "Deployment changed after planning"
            }
            require(attested(plan.spec, current) == plan.previousDigest) { "Running digest changed after planning" }
            replace(plan.spec, current, plan.image)
            try { verify(plan.spec, plan.image, plan.uid) }
            catch (failed: Exception) {
                val latest = deployment(plan.spec)
                require(latest.uid == plan.uid && latest.image == plan.image) { "Rollout was superseded; rollback withheld" }
                replace(plan.spec, latest, plan.previousDigest)
                verify(plan.spec, plan.previousDigest, plan.uid)
                throw IllegalStateException("Release readiness failed; prior digest restored", failed)
            }
            plan.image
        }

    fun rollback(plan: ServerRolloutPlan, latest: DeployReceipt, previous: DeployReceipt, run: String,
        record: (DeployReceipt) -> Unit): DeployReceipt {
        listOf(latest, previous).forEach {
            require(it.node == plan.spec.node && it.environment == plan.spec.environment && it.service == plan.spec.service &&
                it.result == ReceiptStatus.Succeeded && it.remoteVersion != null) { "Rollback needs successful receipts for this server" }
            plan.spec.digest(requireNotNull(it.remoteVersion))
        }
        require(latest.run != previous.run && latest.finishedAtEpochMillis >= previous.finishedAtEpochMillis &&
            latest.rollbackTarget == previous.remoteVersion && plan.previousDigest == latest.remoteVersion &&
            plan.image == previous.remoteVersion) { "Rollback receipts do not describe this plan and its predecessor" }
        return change(plan, run, "rollback", record) {
            val current = deployment(plan.spec)
            require(current.uid == plan.uid && current.image == plan.previousImage && current.index == plan.containerIndex &&
                current.value.getValue("metadata").jsonObject.getValue("resourceVersion").jsonPrimitive.content == plan.resourceVersion) {
                "Deployment changed after rollback planning"
            }
            require(attested(plan.spec, current) == latest.remoteVersion)
            replace(plan.spec, current, plan.image)
            verify(plan.spec, plan.image, plan.uid)
            plan.image
        }
    }

    fun verify(spec: ServerRolloutSpec, image: String, uid: String) {
        spec.digest(image)
        Kubectl.ready(spec)
        val current = deployment(spec)
        val status = current.value.getValue("status").jsonObject
        val generation = current.value.getValue("metadata").jsonObject.getValue("generation").jsonPrimitive.long
        require(current.uid == uid && current.image == image &&
            (status["observedGeneration"]?.jsonPrimitive?.long ?: 0) >= generation &&
            listOf("updatedReplicas", "readyReplicas", "availableReplicas").all { status[it]?.jsonPrimitive?.int == 1 }) {
            "Deployment readiness does not attest the reviewed artifact"
        }
        require(attested(spec, current) == image) { "Ready pod does not attest the reviewed digest" }
    }

    private data class Deployment(val value: JsonObject, val index: Int, val uid: String, val image: String)

    private fun deployment(spec: ServerRolloutSpec): Deployment {
        val value = Kubectl.deployment(spec)
        val metadata = value.getValue("metadata").jsonObject
        require(metadata.getValue("name").jsonPrimitive.content == spec.deployment &&
            metadata.getValue("namespace").jsonPrimitive.content == spec.namespace)
        val desired = value.getValue("spec").jsonObject
        val strategy = desired.getValue("strategy").jsonObject
        val rolling = strategy.getValue("rollingUpdate").jsonObject
        require(desired.getValue("replicas").jsonPrimitive.int == 1 &&
            strategy.getValue("type").jsonPrimitive.content == "RollingUpdate" &&
            rolling.getValue("maxSurge").jsonPrimitive.int == 0 && rolling.getValue("maxUnavailable").jsonPrimitive.int == 1) {
            "Review changed capacity or rollout strategy before deploying"
        }
        val containers = desired.getValue("template").jsonObject.getValue("spec").jsonObject.getValue("containers").jsonArray
        require(containers.size in 1..16)
        val index = containers.indices.single { containers[it].jsonObject.getValue("name").jsonPrimitive.content == spec.container }
        return Deployment(value, index, metadata.getValue("uid").jsonPrimitive.content,
            containers[index].jsonObject.getValue("image").jsonPrimitive.content)
    }

    private fun attested(spec: ServerRolloutSpec, current: Deployment): String {
        val selector = current.value.getValue("spec").jsonObject.getValue("selector").jsonObject
        require(selector["matchExpressions"]?.jsonArray.isNullOrEmpty()) { "Server selector must have exact labels" }
        val labels = selector.getValue("matchLabels").jsonObject
        require(labels.isNotEmpty() && labels.size <= 16)
        val selected = labels.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value.jsonPrimitive.content}" }
        val active = Kubectl.pods(spec, selected).filter { it.jsonObject.getValue("metadata").jsonObject["deletionTimestamp"].let { timestamp -> timestamp == null || timestamp == JsonNull } }
        require(active.size == 1) { "Ready pod count does not match deployment" }
        val pod = active.single().jsonObject
        require(pod.getValue("metadata").jsonObject.getValue("namespace").jsonPrimitive.content == spec.namespace)
        val status = pod.getValue("status").jsonObject.getValue("containerStatuses").jsonArray.single {
            it.jsonObject.getValue("name").jsonPrimitive.content == spec.container
        }.jsonObject
        require(status.getValue("ready").jsonPrimitive.boolean) { "Prior image has no ready pod attestation" }
        return spec.digest(status.getValue("imageID").jsonPrimitive.content.removePrefix("docker-pullable://"))
    }

    private fun replace(spec: ServerRolloutSpec, current: Deployment, image: String) {
        val path = "/spec/template/spec/containers/${current.index}/image"
        Kubectl.patch(spec, buildJsonArray {
            fun entry(operation: String, path: String, value: String) = add(buildJsonObject {
                put("op", operation); put("path", path); put("value", value)
            })
            entry("test", "/metadata/uid", current.uid)
            entry("test", "/metadata/resourceVersion", current.value.getValue("metadata").jsonObject.getValue("resourceVersion").jsonPrimitive.content)
            entry("test", path, current.image)
            entry("replace", path, spec.digest(image))
        })
    }

    private fun change(plan: ServerRolloutPlan, run: String, action: String, record: (DeployReceipt) -> Unit,
        mutate: () -> String): DeployReceipt {
        require(run.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        plan.spec.digest(plan.image); plan.spec.digest(plan.previousDigest)
        val started = System.currentTimeMillis()
        var result: String? = null
        var failure: Exception? = null
        try { result = mutate() } catch (error: Exception) { failure = error }
        val receipt = DeployReceipt(plan.spec.node, plan.spec.environment, plan.spec.service, run, action, plan.gitSha, plan.runner,
            BuildModelReader.digest(json.encodeToString(plan)), plan.image.substringAfter('@'), result, plan.previousDigest,
            if (failure == null) ReceiptStatus.Succeeded else ReceiptStatus.Failed, if (failure == null) "complete" else "rollout",
            started, System.currentTimeMillis(), failure = failure?.javaClass?.simpleName)
        record(receipt)
        failure?.let { throw it }
        return receipt
    }
}
