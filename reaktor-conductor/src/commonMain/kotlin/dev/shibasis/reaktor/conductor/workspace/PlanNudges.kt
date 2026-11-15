package dev.shibasis.reaktor.conductor.workspace

data class PlanReminder(val thread: String, val kind: String, val text: String)
data class PlanEdit(val file: String?, val at: Long?)

object PlanNudges {
    const val QuietMillis = 15 * 60_000L
    const val Draw = "draw"
    const val Flat = "flat"
    const val Doing = "doing"
    const val Links = "links"
    const val Stale = "stale"
    private const val DrawMillis = 5 * 60_000L
    private const val StaleMillis = 30 * 60_000L
    private val Elsewhere = listOf("/.claude/", "/.gemini/", "/.reaktor/", "/private/tmp/", "/tmp/")

    fun of(thread: String, plan: WorkPlan?, startedAt: Long?, edits: List<PlanEdit>, now: Long, briefed: Boolean = false, drawGuide: String, designGuide: String): PlanReminder? {
        val edited = edits.filter { step -> step.file?.let { file -> Elsewhere.none { it in "/$file" } } == true }
        if (plan == null) {
            if (edited.isEmpty() || now - (startedAt ?: now) < DrawMillis) return null
            val files = count(edited.mapNotNull { it.file }.distinct().size)
            return PlanReminder(thread, Draw, if (briefed) "Hangar: this thread still has no plan and you have edited $files. " +
                "Draw one with plan.write {thread: \"$thread\", title, ops}; the first reminder in this thread has the full guide."
                else "Hangar: this thread has no plan yet and you have edited $files. $drawGuide")
        }
        if (plan.items.size >= 4 && plan.components().isEmpty()) return PlanReminder(thread, Flat,
            "Hangar: your plan “${plan.title}” is a flat list of ${plan.items.size} items. $designGuide Redraw it with plan.write {base: ${plan.revision}} " +
                "and keep its statuses: add the components, move items under them with {type: \"update\", item, parent}, and relate the components.")
        val since = edited.filter { (it.at ?: 0) > plan.updatedAt }.mapNotNull { it.file }.distinct()
        if (since.isEmpty()) return null
        val work = plan.leaves()
        if (work.none { it.status == PlanStatus.Doing } && work.any { it.status == PlanStatus.Todo }) return PlanReminder(thread, Doing,
            "Hangar: you are editing ${since.take(3).joinToString { it.substringAfterLast('/') }} but nothing on your plan “${plan.title}” is in progress. " +
                "Mark the item you are on Doing: plan.write {base: ${plan.revision}, ops: [{type: \"status\", item, status: \"Doing\"}]}.")
        val refs = plan.items.flatMap { it.refs }
        val loose = since.filter { file -> refs.none { linked(file, it) } }
        if (loose.size >= 2) return PlanReminder(thread, Links,
            "Hangar: no item on your plan links ${loose.take(5).joinToString { it.substringAfterLast('/') }}${if (loose.size > 5) " and ${loose.size - 5} more" else ""}, " +
                "which you edited after the plan last changed. Add file:, symbol: or module: refs to the items they belong to, or add those items, with plan.write {base: ${plan.revision}}.")
        if (now - plan.updatedAt > StaleMillis) return PlanReminder(thread, Stale,
            "Hangar: your plan “${plan.title}” last changed ${(now - plan.updatedAt) / 60_000} minutes ago while you kept editing. " +
                "Bring its statuses and parts up to date with plan.write {base: ${plan.revision}}.")
        return null
    }

    private fun linked(file: String, ref: String): Boolean {
        val target = ref.substringAfter(':').trim()
        return when (ref.substringBefore(':')) {
            "file" -> target.trimStart('/').let { it.isNotEmpty() && (file.endsWith(it) || it.endsWith(file.trimStart('/'))) }
            "module" -> target.substringAfterLast(':').trim('/').let { it.isNotEmpty() && "/$it/" in "/$file" }
            "symbol" -> file.substringAfterLast('/').substringBeforeLast('.') == target.substringBefore('.')
            else -> false
        }
    }

    private fun count(files: Int) = if (files == 1) "1 file" else "$files files"
}
