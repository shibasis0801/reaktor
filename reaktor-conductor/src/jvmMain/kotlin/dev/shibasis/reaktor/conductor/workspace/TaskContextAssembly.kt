package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ContextEntry
import dev.shibasis.reaktor.conductor.bounded

internal fun AgentWorkspace.taskContext(task: BoardTask, prompt: String, blind: Boolean): TaskContext {
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
    val optional = decisions + memory.entries
    val selected = optional.filterNot { it.ref in plan.excludedContext }
    val packet = requireNotNull(memory.copy(source = "Task context ${plan.id}/${task.id} at graph revision ${view.graph.revision}",
        entries = selected.take(20), notices = memory.notices + "Decisions and memory are optional evidence; they never change the approved task envelope.").bounded(14000))
    val included = packet.entries.map { it.ref }.toSet()
    val excluded = optional.filterNot { it.ref in included }.map {
        it.copy(reason = "${if (it.ref in plan.excludedContext) "Dropped by the person" else "Outside the context size budget"}. ${it.reason}")
    }
    return TaskContext(plan.id, task.id, view.graph.revision, prompt, packet, excluded, notices = notices)
}
