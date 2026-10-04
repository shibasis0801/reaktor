package dev.shibasis.reaktor.devtools

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

interface PlatformInspector {
    suspend fun tree(): List<SemanticsNodeFact>
    suspend fun perform(command: AgentCommand): AgentCommandResult?
}

@Composable
expect fun rememberPlatformInspector(): PlatformInspector?

internal const val MaxNodes = 2000

internal suspend fun <T> onMain(block: () -> T): T {
    val main = runCatching { Dispatchers.Main.also { it.isDispatchNeeded(EmptyCoroutineContext) } }.getOrNull()
    return if (main == null) block() else withContext(main) { block() }
}

internal suspend fun mergedTree(inspector: PlatformInspector?, registry: ElementRegistry): List<SemanticsNodeFact> {
    val registered = registry.snapshot().nodes
    val native = inspector?.tree().orEmpty()
    if (native.isEmpty()) return registered
    val byKey = registered.associateBy { it.id } + registered.filter { it.testTag.isNotBlank() }.associateBy { it.testTag }
    return native.map { node ->
        byKey[node.testTag]?.let { node.copy(graphNodeId = it.graphNodeId, route = it.route) } ?: node
    }
}

internal val inputActions = setOf(
    "tap", "down", "move", "up", "scroll", "type", "key", "back",
    "click", "longClick", "focus", "setText", "activate", "hit",
)

fun inputHandler(inspector: PlatformInspector?, registry: ElementRegistry): CommandHandler =
    commandHandler(AgentCapability.Input, *inputActions.toTypedArray()) { command ->
        when (command.action) {
            "hit" -> {
                val x = command.arguments["x"]?.toFloatOrNull()
                val y = command.arguments["y"]?.toFloatOrNull()
                val node = if (x == null || y == null) null else registry.hitTest(x, y)
                AgentCommandResult(command.id, node != null, node?.let { "Hit ${it.id}" } ?: "Nothing registered there", node?.id)
            }

            "activate" -> {
                val id = command.arguments["id"].orEmpty()
                val registered = registry.activation(id)
                when {
                    registered != null -> {
                        onMain(registered)
                        AgentCommandResult(command.id, true, "Activated $id")
                    }

                    else -> inspector?.perform(command.copy(action = "click"))
                        ?: AgentCommandResult(command.id, false, "Element '$id' has no action")
                }
            }

            else -> inspector?.perform(command)
                ?: AgentCommandResult(command.id, false, "This platform has no input bridge for ${command.action}")
        }
    }
