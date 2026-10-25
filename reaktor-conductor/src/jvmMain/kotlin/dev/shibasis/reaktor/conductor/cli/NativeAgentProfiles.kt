package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.workspace.AgentProfile
import dev.shibasis.reaktor.conductor.workspace.AgentProfileStore
import dev.shibasis.reaktor.conductor.workspace.TaskAccess
import dev.shibasis.reaktor.conductor.workspace.digest
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

internal object NativeAgentProfiles {
    data class Export(val file: File, val text: String)

    fun files(root: File, home: File): Map<String, Export> = AgentProfileStore(
        File(home, ".reaktor/agents/${digest(root.canonicalPath)}/profiles").toPath()).list().mapNotNull { profile ->
        val vendor = when (profile.runtime) { RuntimeKind.Codex -> "codex"; RuntimeKind.ClaudeCode -> "claude"; else -> return@mapNotNull null }
        val name = "reaktor-${digest(root.canonicalPath).take(12)}-${profile.id}"
        val key = "$vendor/$name.${if (vendor == "codex") "toml" else "md"}"
        key to Export(file(root, home, key), content(root, name, profile))
    }.toMap()

    fun file(root: File, home: File, key: String): File {
        val vendor = key.substringBefore('/')
        val name = key.substringAfter('/')
        val prefix = "reaktor-${digest(root.canonicalPath).take(12)}-"
        require(vendor in listOf("codex", "claude") && name.startsWith(prefix) &&
            name.removePrefix(prefix).matches(Regex("[a-z][a-z0-9-]{0,63}\\.${if (vendor == "codex") "toml" else "md"}")))
        return File(home, ".${if (vendor == "codex") "codex" else "claude"}/agents/$name")
    }

    private fun content(root: File, name: String, profile: AgentProfile): String {
        fun quoted(text: String) = JsonPrimitive(text).toString()
        val instructions = "Follow the repository instructions.\nWorkspace: ${root.canonicalPath}\n" +
            "Profile: ${profile.id}, revision ${profile.revision}. Apply this profile only in this workspace.\n" +
            (if (profile.access == TaskAccess.Read) "Work read-only. Do not edit files or run mutating commands.\n" else "Make only the changes assigned by the parent.\n") +
            "Native subagents inherit the parent session's permissions and MCP servers. This export does not create a separate sandbox or change those permissions.\n" +
            "Profile tool rules are instructions unless the native definition enforces them. Additional directories and profile MCP configuration are not installed by this export.\n" +
            "Allowed tools: ${profile.tools.allow.ifEmpty { listOf("inherit") }.joinToString()}. Denied tools: ${profile.tools.deny.ifEmpty { listOf("none") }.joinToString()}.\n" +
            profile.instructions
        val description = "${profile.name}; Reaktor profile ${profile.id} for ${root.name}"
        return if (profile.runtime == RuntimeKind.Codex) buildString {
            appendLine("name = ${quoted(name)}")
            appendLine("description = ${quoted(description)}")
            profile.model?.let { appendLine("model = ${quoted(it)}") }
            profile.effort?.let { appendLine("model_reasoning_effort = ${quoted(it)}") }
            appendLine("developer_instructions = ${quoted(instructions)}")
        } else buildString {
            appendLine("---")
            appendLine("name: ${quoted(name)}")
            appendLine("description: ${quoted(description)}")
            profile.model?.let { appendLine("model: ${quoted(it)}") }
            profile.effort?.let { appendLine("effort: ${quoted(it)}") }
            if (profile.tools.allow.isNotEmpty()) appendLine("tools: [${profile.tools.allow.joinToString { quoted(it) }}]")
            if (profile.tools.deny.isNotEmpty()) appendLine("disallowedTools: [${profile.tools.deny.joinToString { quoted(it) }}]")
            appendLine("---\n")
            appendLine(instructions)
        }
    }
}
