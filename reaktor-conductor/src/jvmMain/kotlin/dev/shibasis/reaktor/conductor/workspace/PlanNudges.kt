package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import java.nio.file.Path

fun PlanNudges(inbox: Path): PlanNudgeInbox = PlanNudgeInbox(inbox)

fun PlanNudges.of(thread: String, plan: WorkPlan?, session: HarnessSession, now: Long, briefed: Boolean = false): PlanReminder? =
    of(thread, plan, session.startedAt, session.steps.map { PlanEdit(it.file, it.at) }, now, briefed, PlanBrief.thread(thread), PlanBrief.Design)

val PlanNudges.Reached: Set<RuntimeKind> get() = setOf(RuntimeKind.ClaudeCode, RuntimeKind.Codex, RuntimeKind.Gemini)

class PlanNudgeInbox(inbox: Path) {
    private val inbox = ThreadInbox(inbox)
    private val sent = HashMap<Pair<String, String>, Long>()

    @Synchronized fun check(sessions: List<HarnessSession>, plans: Map<String, WorkPlan>, now: Long): List<PlanReminder> =
        sessions.filter { it.runtime in PlanNudges.Reached && it.live(now) }.mapNotNull { session ->
            val thread = BoardRefs.session(session.runtime, session.id)
            val reminder = PlanNudges.of(thread, plans[thread], session, now, briefed = (thread to PlanNudges.Draw) in sent) ?: return@mapNotNull null
            if (sent.any { (key, at) -> key.first == thread && now - at < PlanNudges.QuietMillis } || reminder.kind == PlanNudges.Flat && (thread to PlanNudges.Flat) in sent) return@mapNotNull null
            sent[thread to reminder.kind] = now
            inbox.write(thread, reminder.text + "\n")
            reminder
        }
}
