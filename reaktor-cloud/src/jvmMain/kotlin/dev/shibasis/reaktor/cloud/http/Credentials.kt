package dev.shibasis.reaktor.cloud.http

import dev.shibasis.reaktor.core.framework.lenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

@Serializable
data class CredentialName(val file: String, val key: String)

class Credentials(private val workspace: File, private val environment: (String) -> String? = System::getenv) {
    fun resolve(name: CredentialName): String = environment(name.key)?.takeIf { it.isNotBlank() }
        ?: values(File(workspace, name.file))[name.key]?.takeIf { it.isNotBlank() }
        ?: throw MissingCredential(name.file, setOf(name.key))

    private fun values(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        require(file.length() <= MaxBytes) { "${file.name} is larger than a credential file can be" }
        val text = file.readText()
        if (text.trimStart().startsWith("{")) return (lenientJson.parseToJsonElement(text) as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content } }.toMap()
        return text.lineSequence().mapNotNull { line -> Assignment.matchEntire(line.trim())?.destructured?.let { (key, raw) -> key to raw.unquoted() } }.toMap()
    }

    private fun String.unquoted(): String = trim().let { value ->
        if (value.length >= 2 && value.first() == value.last() && value.first() in "'\"") value.substring(1, value.lastIndex) else value
    }

    companion object {
        private const val MaxBytes = 262_144L
        private val Assignment = Regex("(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)")
    }
}
