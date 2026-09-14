package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.portgraph.attach.Attachable
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.service.InterceptorStage
import dev.shibasis.reaktor.service.Service

/**
 * A command handler assembled from a lambda.
 *
 * Apps have their own idea of what "navigate" means, so the agent supplies the plumbing and the
 * app supplies the verb. This keeps the SDK free of any particular app's navigation model while
 * still giving the workbench a uniform command surface.
 */
fun commandHandler(
    capability: String,
    vararg actions: String,
    execute: suspend (AgentCommand) -> AgentCommandResult,
): CommandHandler = object : CommandHandler {
    override val capability: String = capability
    override val actions: Set<String> = actions.toSet()
    override suspend fun execute(command: AgentCommand): AgentCommandResult = execute(command)
}

/** Wires the traffic tap into one service, for an app that would rather not install it globally. */
fun DevToolsAgent.instrument(service: Service): Service =
    service.use(setOf(InterceptorStage.CLIENT_APPLICATION), trafficTap)

/**
 * Captures every service in the process.
 *
 * The default, because the alternative is remembering to instrument each service as it is written,
 * and the call that matters is always the one in the service nobody remembered.
 */
fun DevToolsAgent.instrumentAllServices(): () -> Unit = Service.installGlobal(trafficTap)

/**
 * Installs the graph taps on one port owner and everything it owns.
 *
 * Both halves: the K1 interceptor sees calls crossing a port, the lifecycle listener sees the
 * graph change shape. A graph can do either without the other, so neither implies the other.
 */
fun DevToolsAgent.instrumentPorts(owner: PortCapability): () -> Unit =
    installGraphTaps(owner, owner.ownedPorts(), portEvents)

/**
 * Installs the taps across a whole graph.
 *
 * Ports belong to nodes, not to the graph, so this walks the nodes. Applied at startup it catches
 * the graph as built; nodes attached later need their own call, which is why the undo is returned
 * rather than assumed to be permanent.
 */
fun <N : PortNode<*>> DevToolsAgent.instrumentGraph(graph: PortGraph<*, N>): () -> Unit {
    val undos = graph.nodes.map { node -> instrumentPorts(node) }
    return { undos.forEach { it() } }
}

private fun PortCapability.ownedPorts(): List<Attachable> = buildList {
    listOf(consumerPorts.snapshot(), providerPorts.snapshot()).forEach { byType ->
        byType.values.forEach { byKey -> addAll(byKey.values) }
    }
}

/**
 * Runtime overrides the app can read.
 *
 * Scoped to the app rather than to the OS on purpose: changing the device's font scale to test one
 * screen changes it for every app the developer has open, and reverting it is another round trip.
 */
class OverrideStore {
    private val values = mutableMapOf<String, String>()

    operator fun get(key: String): String? = values[key]

    fun snapshot(): Map<String, String> = values.toMap()

    fun handler(
        onChange: (key: String, value: String?) -> Unit = { _, _ -> },
    ): CommandHandler = commandHandler(
        AgentCapability.Overrides,
        "set",
        "clear",
        "list",
    ) { command ->
        when (command.action) {
            "set" -> {
                val key = command.arguments["key"].orEmpty()
                val value = command.arguments["value"].orEmpty()
                if (key.isBlank()) {
                    AgentCommandResult(command.id, false, "An override needs a key")
                } else {
                    values[key] = value
                    onChange(key, value)
                    AgentCommandResult(command.id, true, "$key = $value")
                }
            }

            "clear" -> {
                val key = command.arguments["key"].orEmpty()
                if (key.isBlank()) {
                    val cleared = values.keys.toList()
                    values.clear()
                    cleared.forEach { onChange(it, null) }
                    AgentCommandResult(command.id, true, "Cleared ${cleared.size} overrides")
                } else {
                    values.remove(key)
                    onChange(key, null)
                    AgentCommandResult(command.id, true, "Cleared $key")
                }
            }

            else -> AgentCommandResult(
                command.id,
                true,
                "${values.size} overrides",
                payload = values.entries.joinToString(";") { "${it.key}=${it.value}" },
            )
        }
    }
}

/** Changes what the agent records without a rebuild. */
fun DevToolsAgent.logLevelHandler(): CommandHandler =
    commandHandler(AgentCapability.Logs, "level", "clear") { command ->
        when (command.action) {
            "level" -> {
                val level = command.arguments["level"]
                    ?.let { name -> LogLevel.entries.firstOrNull { it.name.equals(name, true) } }
                if (level == null) {
                    AgentCommandResult(command.id, false, "Unknown level; use one of ${LogLevel.entries}")
                } else {
                    log.setMinimumLevel(level)
                    AgentCommandResult(command.id, true, "Minimum level is now $level")
                }
            }

            else -> {
                logs.clear()
                AgentCommandResult(command.id, true, "Log buffer cleared")
            }
        }
    }
