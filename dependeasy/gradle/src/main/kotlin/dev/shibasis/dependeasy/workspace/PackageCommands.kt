package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import java.io.File

internal fun packageCommands(build: String, root: File, manifests: Collection<File>): List<JsonObject> =
    manifests.filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.flatMap { file ->
        val manifest = Json.parseToJsonElement(file.readText()).jsonObject
        val directory = file.parentFile.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
        val owned = manifest["dependeasy"]?.jsonObject?.get("generatedScripts")?.jsonArray.orEmpty()
            .map { it.jsonPrimitive.content }.toSet()
        manifest["scripts"]?.jsonObject.orEmpty().map { (name, script) -> buildJsonObject {
            val path = ":package:$directory:$name"
            put("id", build + path); put("task", path); put("kind", "package"); put("runner", "pnpm")
            put("directory", directory); put("name", name); put("script", script); put("platform", "unspecified")
            putJsonArray("command") { listOf("pnpm", "--dir", directory, "run", name).forEach { add(it) } }
            putJsonArray("requires") {
                if (name in owned && script.jsonPrimitive.content.matches(Regex("\\./gradlew :[A-Za-z0-9:_-]+"))) add(buildJsonObject {
                    put("build", build); put("task", script.jsonPrimitive.content.removePrefix("./gradlew "))
                })
            }
        } }
    }

internal fun withPackageCommand(target: JsonObject, commands: List<JsonObject>): JsonObject {
    val script = target["command"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    if (script.size != 3 || script.take(2) != listOf("pnpm", "run")) return target
    val command = commands.singleOrNull { it.text("directory") == "." && it.text("name") == script.last() }
        ?: return target
    return JsonObject(target + ("requires" to JsonArray(target.getValue("requires").jsonArray + buildJsonObject {
        put("build", command.text("id").substringBefore(':')); put("task", command.text("task"))
    })))
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
