package dev.shibasis.reaktor.tooling.delivery

import kotlinx.serialization.json.*
import java.io.File

/** Read generated entrypoint declarations; never infer a worker binding from a shell body. */
object GradlePackageTargets {
    data class WorkerDeploy(val task: String, val directory: File, val environments: Set<String>)

    fun workerDeploy(root: File, manifest: JsonObject, script: String, command: String): WorkerDeploy? {
        val metadata = manifest["dependeasy"] as? JsonObject ?: return null
        val owned = metadata["generatedScripts"] as? JsonArray ?: return null
        if (owned.none { (it as? JsonPrimitive)?.contentOrNull == script }) return null
        val target = (metadata["generatedTargets"] as? JsonObject)?.get(script) as? JsonObject ?: return null
        fun text(key: String) = (target[key] as? JsonPrimitive)?.contentOrNull
        val task = text("task") ?: return null
        if (!task.matches(Regex(":[a-z][A-Za-z0-9]*")) || command.trim() != "./gradlew $task") return null
        if (text("effect") != "deploy" || text("worker").isNullOrBlank()) return null
        val relative = text("workerDirectory") ?: return null
        if (File(relative).isAbsolute || relative.split('/', '\\').any { it == ".." }) return null
        val canonicalRoot = root.canonicalFile
        val directory = canonicalRoot.resolve(relative).canonicalFile
        if (directory == canonicalRoot || !directory.toPath().startsWith(canonicalRoot.toPath()) || !directory.isDirectory) return null
        val environments = (target["environments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return null
        if (environments.isEmpty()) return null
        return WorkerDeploy(task, directory, environments)
    }
}
