package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessCheck
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.conductor.worklog.PlanState

object PlanMirror {
    fun of(session: HarnessSession, fallback: PlanStatus): WorkPlan = of(BoardRefs.session(session.runtime, session.id), session.title, session.runtime.agent(),
        session.checklist, session.files, session.startedAt, session.checklistAt ?: session.lastActiveAt, fallback)

    fun of(thread: String, title: String, agent: String, checklist: List<HarnessCheck>, files: List<String>, startedAt: Long?, at: Long, fallback: PlanStatus): WorkPlan {
        val by = BoardActor(BoardActorKind.Agent, agent, thread)
        val taken = HashSet<String>()
        val keys = checklist.map { key(it.text, taken) }
        val items = if (checklist.isEmpty()) listOf(PlanItem("prompt", line(title), status = fallback, refs = files.take(PlanRules.MaxRefs).map { "file:$it" }, by = by, changedAt = at))
        else checklist.take(PlanRules.MaxOps - 1).mapIndexed { index, check ->
            PlanItem(keys[index], line(check.text), status = check.state.status(), after = listOfNotNull(keys.getOrNull(index - 1)),
                refs = check.files.take(PlanRules.MaxRefs).map { "file:$it" }, by = by, changedAt = at)
        }
        return WorkPlan(thread, line(title), items = items, createdBy = by, createdAt = startedAt ?: at, updatedAt = at)
    }

    fun ops(plan: WorkPlan): List<PlanOp> = plan.items.map { PlanOp.Add(it.title, it.id, it.detail, it.status, it.parent, it.after, it.refs, it.at) }

    private fun PlanState.status() = when (this) {
        PlanState.Pending -> PlanStatus.Todo
        PlanState.Active -> PlanStatus.Doing
        PlanState.Done -> PlanStatus.Done
    }

    fun RuntimeKind.agent() = when (this) {
        RuntimeKind.Codex -> "Codex"
        RuntimeKind.Gemini -> "Antigravity"
        else -> "Claude Code"
    }

    private fun line(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 200) it.take(199) + "…" else it }.ifEmpty { "Untitled" }

    private fun key(text: String, taken: MutableSet<String>): String {
        val stem = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trimEnd('-')
            .let { if (it.firstOrNull()?.isLetter() == true) it else "s-$it".trimEnd('-') }.ifEmpty { "step" }
        return generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem-$it" }.first(taken::add)
    }
}
