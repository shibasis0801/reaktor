package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ContextEntry
import dev.shibasis.reaktor.conductor.ContextPacket
import dev.shibasis.reaktor.conductor.bounded
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal suspend fun AgentWorkspace.taskContext(task: BoardTask, prompt: String, blind: Boolean): TaskContext {
    val plan = requireNotNull(task.graph)
    val view = board.view(plan.id)
    val notices = listOf("This is the task prompt and optional context for this graph/source snapshot. The native turn adds candidate evidence, repository instructions and provider tool policy.")
    if (blind) return TaskContext(plan.id, task.id, view.graph.revision, prompt,
        notices = notices + "Independent review starts without retained memory or peer decisions. Private evidence reads open after all independent stages submit their results.")
    val memory = memory.search("", null, false, plan.id, task.id)
    val refs = setOf(plan.id, task.id) + plan.dependsOn.map { it.task }
    val superseded = view.decisions.mapNotNull { it.supersedes }.toSet()
    val decisions = view.decisions.filter { it.id !in superseded && it.subjects.any(refs::contains) }.asReversed().map {
        ContextEntry("decision:${it.id}", "task-decision", it.statement.take(200), "${it.statement}\nRationale: ${it.rationale}",
            "Current decision for ${it.subjects.joinToString()}; author=${it.author?.name}; at=${it.at}; supersedes=${it.supersedes ?: "none"}; personAccepted=${it.personAccepted}. Concurrent unsuperseded decisions remain claims until settled.")
    }
    val atlas = try {
        if (task.scope.isEmpty()) memory.copy(entries = emptyList(), notices = listOf("No task scope is declared for an Atlas slice."))
        else AgentWorkspaceJson.decodeFromJsonElement(ContextPacket.serializer(), readKernel("atlas_task_context",
            buildJsonObject { put("scopes", JsonArray(task.scope.map(::JsonPrimitive))) }))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        memory.copy(entries = emptyList(), partial = true, notices = listOf("Atlas task context is unavailable: ${failure.message?.take(180)}"))
    }
    val optional = atlas.entries + decisions + memory.entries
    val selectedAtlas = atlas.copy(entries = atlas.entries.filterNot { it.ref in plan.excludedContext }).bounded(4000)
    val selectedMemory = memory.copy(entries = (decisions + memory.entries).filterNot { it.ref in plan.excludedContext }
        .take(20 - selectedAtlas?.entries.orEmpty().size.coerceAtMost(20))).bounded(10000)
    val packet = requireNotNull(memory.copy(source = "Task context ${plan.id}/${task.id} at graph revision ${view.graph.revision}",
        entries = selectedAtlas?.entries.orEmpty().take(20) + selectedMemory?.entries.orEmpty(),
        partial = atlas.partial || selectedAtlas?.partial == true || selectedMemory?.partial == true,
        notices = memory.notices + atlas.notices + "Decisions, memory and Atlas slices are optional evidence; they never change the approved task envelope. Atlas uses at most 4,000 optional-context characters.").bounded(14000))
    val included = packet.entries.map { it.ref }.toSet()
    val excluded = optional.filterNot { it.ref in included }.map {
        it.copy(reason = "${if (it.ref in plan.excludedContext) "Dropped by the person" else "Outside the context size budget"}. ${it.reason}")
    }
    return TaskContext(plan.id, task.id, view.graph.revision, prompt, packet, excluded, notices = notices)
}
