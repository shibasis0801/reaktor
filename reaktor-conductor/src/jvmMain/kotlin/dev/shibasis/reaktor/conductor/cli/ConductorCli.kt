package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.Conductor
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.EchoRuntime
import dev.shibasis.reaktor.conductor.Protocol
import dev.shibasis.reaktor.conductor.Rosters
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.ThreadDocument
import dev.shibasis.reaktor.conductor.ThreadId
import dev.shibasis.reaktor.conductor.AgentSpec
import dev.shibasis.reaktor.conductor.ContextPacket
import dev.shibasis.reaktor.conductor.UsageSummary
import dev.shibasis.reaktor.conductor.EventKind
import dev.shibasis.reaktor.conductor.usageSummary
import dev.shibasis.reaktor.conductor.store.FileThreadStore
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

private const val USAGE = """
reaktor conductor - run a prompt through one agent, all agents, a council, or a pipeline.

  --prompt <text>      the task (required)
  --dir <path>         working directory the agents read (default: current directory)
  --protocol <name>    ask | all | council | pipeline | planned   (default: ask)
  --plan <file>        a Protocol as JSON: your own topology, used instead of --protocol
  --agents <a,b,c>     agent ids to involve (default: every participant)
  --roster <file>      JSON array of AgentSpec: your own agents (default: a small sample roster)
  --thread <file>      read and write the canonical thread here
  --context <file>     scoped ContextPacket JSON (e.g. Manna context_for_task result data)
  --dry                use the offline Echo runtime; spawns nothing
  --isolate-config     run harnesses without your own config (reproducible; ignores your setup)
  --quiet              do not stream agent output

The built-in roster and pipeline are samples. Real use is --roster with your own agents and
--plan with your own topology, or --protocol planned to let an agent design both.

Examples:
  --protocol council --prompt "Should reaktor-db own sync, or should reaktor-service?"
  --roster team.json --plan review.json --prompt "Harden the notification path"
  --protocol planned --agents architect --prompt "Port the FFI wire to credit backpressure"
  --protocol ask --agents skeptic --prompt "What breaks in this design?"
"""

fun main(args: Array<String>): Unit = runBlocking {
    if (args.firstOrNull() == "workspace") {
        workspaceCli(args.drop(1))
        return@runBlocking
    }
    val options = parse(args)
    if (options == null || options.prompt.isBlank()) {
        println(USAGE.trim())
        exitProcess(if (options == null) 2 else 0)
    }

    val directory = File(options.dir).canonicalFile
    if (!directory.isDirectory) {
        System.err.println("Not a directory: ${directory.absolutePath}")
        exitProcess(2)
    }

    val loaded = options.roster?.let { path ->
        ConductorJson.decodeFromString(ListSerializer(AgentSpec.serializer()), File(path).readText())
    } ?: Rosters.default
    val roster = if (options.isolateConfig) {
        loaded.map { it.copy(tools = it.tools.copy(isolateOperatorConfig = true)) }
    } else {
        loaded
    }

    val threadFile = options.thread?.let(::File)
    val context = options.context?.let { path ->
        val file = File(path)
        require(file.length() <= 1_000_000) { "Context file exceeds 1 MB" }
        ConductorJson.decodeFromString(ContextPacket.serializer(), file.readText())
    }
    val store = threadFile?.let { FileThreadStore.open(it.toPath()) }
    var failed: Boolean
    try {
        val thread = store?.load()
            ?: ThreadDocument(
                id = ThreadId(UUID.randomUUID().toString()),
                title = options.prompt.take(80),
                participants = roster,
            )

        val executor = SupervisedProcessExecutor()
        val runtimes: Map<RuntimeKind, AgentRuntime> = if (options.dry) {
            mapOf(
                RuntimeKind.Echo to EchoRuntime(),
                RuntimeKind.ClaudeCode to EchoRuntime(RuntimeKind.ClaudeCode),
                RuntimeKind.Codex to EchoRuntime(RuntimeKind.Codex),
            )
        } else {
            mapOf(
                RuntimeKind.ClaudeCode to ClaudeCodeRuntime(executor),
                RuntimeKind.Codex to CodexRuntime(executor),
                RuntimeKind.Echo to EchoRuntime(),
            )
        }

        val ids = options.agents.map(::AgentId)
        val protocol = when {
            // Any topology the operator wrote, loaded verbatim.
            options.plan != null ->
                ConductorJson.decodeFromString(Protocol.serializer(), File(options.plan).readText())

            options.protocol == "ask" -> Protocol.Ask(ids.firstOrNull() ?: thread.participants.first().id)
            options.protocol == "all" -> Protocol.All(ids)
            options.protocol == "planned" -> Protocol.Planned(ids.firstOrNull() ?: roster.first().id)
            // The built-in pipeline is one sample, not the feature; --plan replaces it wholesale.
            options.protocol == "pipeline" -> Rosters.designPipeline
            else -> Protocol.Council(ids)
        }

        val conductor = Conductor(
            runtimes = runtimes,
            clock = System::currentTimeMillis,
            idFactory = { dev.shibasis.reaktor.conductor.EventId(UUID.randomUUID().toString()) },
        )

        val label = when (protocol) {
            is Protocol.Ask -> "ask ${protocol.agent.value}"
            is Protocol.All -> "all"
            is Protocol.Council -> "council"
            is Protocol.Pipeline -> "pipeline (${protocol.stages.size} stages)"
            is Protocol.Planned -> "planned by ${protocol.planner.value}"
        }
        println("thread ${thread.id.value} · $label · ${directory.absolutePath}")
        val result = try {
            conductor.run(
                thread = thread,
                prompt = options.prompt,
                protocol = protocol,
                workingDirectory = directory.absolutePath,
                context = context,
                onCheckpoint = { store?.checkpoint(it) },
            ) { event ->
                if (!options.quiet) render(event)
            }
        } finally {
            executor.close()
        }

        println()
        result.added.forEach { event ->
            println("── ${event.kind.name.lowercase()} · round ${event.round} · ${event.id.value}")
            println(event.text.trim())
            println()
        }

        println("usage ${ConductorJson.encodeToString(UsageSummary.serializer(), result.thread.usageSummary())}")
        threadFile?.let { println("thread written to ${it.absolutePath}") }
        failed = result.added.any { it.kind == EventKind.Failure }
    } finally {
        store?.close()
    }
    if (failed) exitProcess(1)
}

private fun render(event: AgentEvent) {
    when (event) {
        is AgentEvent.Started -> println("  ${event.agent.value} started")
        is AgentEvent.ToolUse -> println("  ${event.agent.value} · ${event.tool}")
        is AgentEvent.Finished -> println(
            "  ${event.agent.value} ${if (event.outcome.ok) "done" else "failed: ${event.outcome.failure}"}",
        )

        is AgentEvent.RequestPending -> println("  ${event.agent.value} waiting: ${event.request.title}${event.request.scope?.let { " · $it" }.orEmpty()}")
        is AgentEvent.RequestResolved -> Unit

        is AgentEvent.Delta, is AgentEvent.Reasoning -> Unit
    }
}

private data class Options(
    val prompt: String = "",
    val dir: String = ".",
    val protocol: String = "ask",
    val agents: List<String> = emptyList(),
    val roster: String? = null,
    val plan: String? = null,
    val thread: String? = null,
    val context: String? = null,
    val dry: Boolean = false,
    val quiet: Boolean = false,
    val isolateConfig: Boolean = false,
)

private fun parse(args: Array<String>): Options? {
    var options = Options()
    var index = 0
    while (index < args.size) {
        val flag = args[index]
        fun value(): String? = args.getOrNull(index + 1)?.also { index++ }
        options = when (flag) {
            "--prompt" -> options.copy(prompt = value() ?: return null)
            "--dir" -> options.copy(dir = value() ?: return null)
            "--protocol" -> options.copy(protocol = (value() ?: return null).also {
                if (it !in setOf("ask", "all", "council", "pipeline", "planned")) return null
            })
            "--agents" -> options.copy(
                agents = (value() ?: return null).split(",").map(String::trim).filter(String::isNotEmpty),
            )

            "--roster" -> options.copy(roster = value() ?: return null)
            "--plan" -> options.copy(plan = value() ?: return null)
            "--thread" -> options.copy(thread = value() ?: return null)
            "--context" -> options.copy(context = value() ?: return null)
            "--dry" -> options.copy(dry = true)
            "--isolate-config" -> options.copy(isolateConfig = true)
            "--quiet" -> options.copy(quiet = true)
            "--help", "-h" -> return Options()
            else -> return null
        }
        index++
    }
    return options
}
