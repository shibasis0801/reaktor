package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
enum class SeatTier(val title: String, val summary: String, val floor: Int, val ceiling: Int?) {
    Frontier("Frontier", "Index 55 or more; spend it where judgement matters", 55, null),
    Strong("Strong", "Index 45 to 54; most building work", 45, 54),
    Fast("Fast", "Index 30 to 44; cheap and quick, for narrow tasks", 30, 44);

    fun fits(index: Int?): Boolean = index != null && index >= floor && (ceiling == null || index <= ceiling)
}

@Serializable
data class StructureSeat(val key: String, val role: String, val duty: String, val tier: SeatTier, val config: String)

@Serializable
data class StructureLink(val from: String, val to: String, val label: String)

@Serializable
data class StructureTemplate(val id: String, val title: String, val summary: String, val seats: List<StructureSeat>, val links: List<StructureLink>) {
    fun seat(key: String): StructureSeat? = seats.firstOrNull { it.key == key }
}

object AgentStructures {
    private fun seat(key: String, role: String, duty: String, tier: SeatTier, config: String) = StructureSeat(key, role, duty, tier, config)
    private fun link(from: String, to: String, label: String) = StructureLink(from, to, label)

    val templates: List<StructureTemplate> = listOf(
        StructureTemplate("council", "Council", "Several models answer the same question on their own; a chair weighs the answers and decides.",
            listOf(
                seat("chair", "Chair", "Weighs the answers and decides", SeatTier.Frontier, "judge"),
                seat("member-a", "Member", "Answers independently", SeatTier.Strong, "principal-architect"),
                seat("member-b", "Member", "Answers independently; pick another family", SeatTier.Strong, "principal-architect"),
                seat("member-c", "Member", "Answers independently; pick a third family", SeatTier.Strong, "principal-architect"),
            ),
            listOf(link("member-a", "chair", "answer"), link("member-b", "chair", "answer"), link("member-c", "chair", "answer"))),
        StructureTemplate("planner-engineer-tester", "Planner · Engineer · Tester", "A plan, a build and an independent test, looping until the tests pass.",
            listOf(
                seat("planner", "Planner", "Turns the request into tasks and checks", SeatTier.Frontier, "planner"),
                seat("engineer", "Engineer", "Builds each task", SeatTier.Strong, "systems-builder"),
                seat("tester", "Tester", "Proves the change with flows and tests", SeatTier.Fast, "verification-gate"),
            ),
            listOf(link("planner", "engineer", "plan"), link("engineer", "tester", "change"), link("tester", "engineer", "failures"), link("tester", "planner", "result"))),
        StructureTemplate("adversarial", "Adversarial", "A builder and a critic from another family argue it out; a judge settles what they disagree on.",
            listOf(
                seat("builder", "Builder", "Writes the change", SeatTier.Strong, "systems-builder"),
                seat("critic", "Critic", "Attacks the change; pick another family", SeatTier.Frontier, "adversarial-critic"),
                seat("judge", "Judge", "Settles disputes", SeatTier.Frontier, "judge"),
            ),
            listOf(link("builder", "critic", "change"), link("critic", "builder", "findings"), link("critic", "judge", "dispute"), link("builder", "judge", "rebuttal"))),
        StructureTemplate("top-down-bottom-up", "Top-down · Bottom-up", "A scout maps the code from below while an architect splits the work from above; engineers build the pieces.",
            listOf(
                seat("architect", "Architect", "Splits the work into pieces", SeatTier.Frontier, "principal-architect"),
                seat("scout", "Scout", "Maps callers and blast radius", SeatTier.Fast, "atlas-cartographer"),
                seat("engineer-a", "Engineer", "Builds one piece", SeatTier.Strong, "systems-builder"),
                seat("engineer-b", "Engineer", "Builds another piece", SeatTier.Strong, "systems-builder"),
            ),
            listOf(link("scout", "architect", "map"), link("architect", "engineer-a", "piece"), link("architect", "engineer-b", "piece"),
                link("engineer-a", "architect", "questions"), link("engineer-b", "architect", "questions"))),
        StructureTemplate("planner-engineer-optimiser-regulariser", "Planner · Engineer · Optimiser · Regulariser",
            "Build it, make it fast, make it simple, then loop back to the plan.",
            listOf(
                seat("planner", "Planner", "Plans the change", SeatTier.Frontier, "planner"),
                seat("engineer", "Engineer", "Makes it work", SeatTier.Strong, "systems-builder"),
                seat("optimiser", "Optimiser", "Makes it fast", SeatTier.Strong, "performance-surgeon"),
                seat("regulariser", "Regulariser", "Makes it simple", SeatTier.Strong, "regulariser"),
            ),
            listOf(link("planner", "engineer", "plan"), link("engineer", "optimiser", "working change"), link("optimiser", "regulariser", "faster change"),
                link("regulariser", "planner", "simpler change"))),
        StructureTemplate("lead-workers", "Lead and workers", "One strong lead splits the work; cheap workers do the pieces in parallel.",
            listOf(
                seat("lead", "Lead", "Splits the work and reviews results", SeatTier.Frontier, "planner"),
                seat("worker-a", "Worker", "Does one piece", SeatTier.Fast, "systems-builder"),
                seat("worker-b", "Worker", "Does one piece", SeatTier.Fast, "systems-builder"),
                seat("worker-c", "Worker", "Does one piece", SeatTier.Fast, "systems-builder"),
            ),
            listOf(link("lead", "worker-a", "task"), link("lead", "worker-b", "task"), link("lead", "worker-c", "task"),
                link("worker-a", "lead", "result"), link("worker-b", "lead", "result"), link("worker-c", "lead", "result"))),
        StructureTemplate("escalation-ladder", "Escalation ladder", "Start with the cheapest model and hand over to a stronger one only when it gets stuck.",
            listOf(
                seat("first", "First try", "Attempts the task", SeatTier.Fast, "systems-builder"),
                seat("second", "Second try", "Takes over when the first is stuck", SeatTier.Strong, "systems-builder"),
                seat("last", "Last resort", "Takes over when the second is stuck", SeatTier.Frontier, "systems-builder"),
            ),
            listOf(link("first", "second", "stuck"), link("second", "last", "stuck"))),
        StructureTemplate("best-of-n", "Best of N", "Several builders try the same task; a judge keeps the best result.",
            listOf(
                seat("builder-a", "Builder", "Tries the task", SeatTier.Strong, "systems-builder"),
                seat("builder-b", "Builder", "Tries the task; pick another family", SeatTier.Strong, "systems-builder"),
                seat("builder-c", "Builder", "Tries the task; pick a third family", SeatTier.Strong, "systems-builder"),
                seat("judge", "Judge", "Keeps the best result", SeatTier.Frontier, "judge"),
            ),
            listOf(link("builder-a", "judge", "candidate"), link("builder-b", "judge", "candidate"), link("builder-c", "judge", "candidate"))),
        StructureTemplate("pair", "Pair", "A driver writes while a navigator reviews as it goes.",
            listOf(
                seat("driver", "Driver", "Writes the change", SeatTier.Strong, "systems-builder"),
                seat("navigator", "Navigator", "Reviews as it goes", SeatTier.Frontier, "adversarial-critic"),
            ),
            listOf(link("driver", "navigator", "diff"), link("navigator", "driver", "review"))),
    )

    private val byId = templates.associateBy { it.id }

    fun template(id: String): StructureTemplate? = byId[id]
}
