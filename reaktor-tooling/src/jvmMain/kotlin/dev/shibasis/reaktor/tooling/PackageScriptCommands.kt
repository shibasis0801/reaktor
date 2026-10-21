package dev.shibasis.reaktor.tooling

import kotlinx.serialization.json.*

/** Literal package-script references; arbitrary shell syntax remains outside this parser. */
object PackageScriptCommands {
    data class Reference(val script: String, val workspace: String?)
    private val run = Regex(
        """(?:^|[;&|]\s*|--\s+)(?:npm|pnpm)\s+(?:--(?:workspace|filter)(?:=|\s+)([^\s;&|]+)\s+)?run\s+([^\s;&|]+)(?:\s+--workspace(?:=|\s+)([^\s;&|]+))?""",
    )
    private val selector = Regex("""--(?:workspace|filter)(?:=|\s+)([^\s;&|]+)""")

    fun authoredScripts(manifest: JsonObject): Map<String, String> {
        val generated = ((manifest["dependeasy"] as? JsonObject)?.get("generatedScripts") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        return (manifest["scripts"] as? JsonObject)?.filterKeys { it !in generated }
            ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
            ?.toMap().orEmpty()
    }

    fun references(body: String): List<Reference> = run.findAll(body).map {
        Reference(it.groupValues[2], (it.groupValues[1].ifEmpty { it.groupValues[3] }).trim('"', '\'').ifEmpty { null })
    }.toList()

    fun workspaces(body: String): List<String> = selector.findAll(body)
        .map { it.groupValues[1].trim('"', '\'') }.distinct().toList()

    fun exactWorkspaceDeploy(body: String): String? {
        val match = run.matchEntire(body.trim()) ?: return null
        return references(match.value).single().takeIf { it.script == "deploy" }?.workspace
    }

    fun expand(body: String, lookup: (Reference) -> String?): String {
        val bodies = mutableListOf(body)
        val visited = mutableSetOf<Reference>()
        var index = 0
        while (index < bodies.size) {
            references(bodies[index++]).forEach { reference ->
                if (visited.add(reference)) lookup(reference)?.let(bodies::add)
            }
        }
        return bodies.joinToString("\n")
    }
}
