package dev.shibasis.reaktor.devtools

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

class PortWatches {
    private val keys = atomic(emptySet<String>())

    val watched: Set<String> get() = keys.value

    fun watching(node: String?, port: String): Boolean = keys.value.let { it.isNotEmpty() && key(node, port) in it }

    fun add(node: String, port: String) = keys.update { it + key(node, port) }

    fun remove(node: String, port: String) = keys.update { it - key(node, port) }

    fun clear() {
        keys.value = emptySet()
    }

    companion object {
        fun key(node: String?, port: String): String = "${node.orEmpty()}|$port"
    }
}

internal fun DevToolsAgent.portWatchHandler(): CommandHandler =
    commandHandler(AgentCapability.PortWatch, "add", "remove", "clear") { command ->
        val node = command.arguments["node"].orEmpty()
        val port = command.arguments["port"].orEmpty()
        when {
            !policy.captureValues -> AgentCommandResult(command.id, false, "This build does not capture values")
            command.action == "clear" -> {
                watches.clear()
                AgentCommandResult(command.id, true, "Watching nothing")
            }
            port.isBlank() -> AgentCommandResult(command.id, false, "A port is required")
            command.action == "add" -> {
                watches.add(node, port)
                AgentCommandResult(command.id, true, "Watching $port")
            }
            else -> {
                watches.remove(node, port)
                AgentCommandResult(command.id, true, "Stopped watching $port")
            }
        }
    }

internal fun watchedValue(value: Any?): String? {
    if (value == null || value == Unit) return null
    val text = when (value) {
        is Collection<*> -> "${value::class.simpleName}(${value.size}) " +
            value.take(3).joinToString(prefix = "[", postfix = if (value.size > 3) ", …]" else "]") { it.toString().take(160) }
        is Map<*, *> -> "Map(${value.size}) " +
            value.entries.take(3).joinToString(prefix = "{", postfix = if (value.size > 3) ", …}" else "}") { "${it.key}=${it.value.toString().take(120)}" }
        is ByteArray -> "ByteArray(${value.size})"
        else -> value.toString()
    }
    if (identityOnly.matches(text)) return null
    return text.take(ValueLimit * 2).masked().take(ValueLimit)
}

private val identityOnly = Regex("^[\\w.$]+@[0-9a-f]+$")
private const val ValueLimit = 600
