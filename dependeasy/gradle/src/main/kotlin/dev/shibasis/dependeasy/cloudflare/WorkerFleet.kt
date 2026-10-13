package dev.shibasis.dependeasy.cloudflare

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.web.JavaScriptRuntime
import kotlinx.serialization.json.*
import org.gradle.api.Project
import java.io.File

class WorkerFleet internal constructor(private val project: Project) {
    private val workers = linkedMapOf<String, File>()
    val prerequisites = mutableListOf<String>()
    fun include(vararg names: String) { names.forEach { worker(it, "targets/${it}Server") } }
    fun worker(name: String, directory: Any) { require(workers.put(name, project.file(directory)) == null) { "Duplicate worker: $name" } }

    internal fun register(workspace: BuildWorkspace) {
        val configs = workers.mapValues { Json.parseToJsonElement(it.value.resolve("wrangler.json").readText()).jsonObject }
        val names = configs.entries.associate { it.value.getValue("name").jsonPrimitive.content to it.key }
        val extension = DependeasyExtension.get(project)
        val runtime = JavaScriptRuntime(project)
        workers.forEach { (name, directory) ->
            val command = extension.javascript("${name}Worker", directory).command("deploy", null,
                "exec", "node", runtime.file("cli/worker-deploy.ts").canonicalPath)
            command.configure {
                sourceFiles.from(project.rootProject.file("wrangler.json"), runtime.sources)
                outputs.upToDateWhen { false }
            }
            val bindings = configs.getValue(name)["services"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("service").jsonPrimitive.content }
            workspace.target("deploy${name.replaceFirstChar(Char::uppercaseChar)}") {
                effect = "deploy"; platform = "linux"; worker = name
                workerDirectory = project.relativePath(directory)
                environments.addAll(listOf("dev", "prod"))
                task(command); tasks(*prerequisites.toTypedArray())
                this.bindings.addAll(bindings)
                tasks(*bindings.mapNotNull(names::get).filter { it != name }.map { ":deploy${it.replaceFirstChar(Char::uppercaseChar)}" }.toTypedArray())
            }
        }
        workspace.target("deployWorkers") {
            effect = "deploy"; platform = "linux"
            environments.addAll(listOf("dev", "prod"))
            tasks(*workers.keys.map { ":deploy${it.replaceFirstChar(Char::uppercaseChar)}" }.toTypedArray())
        }
    }
}
