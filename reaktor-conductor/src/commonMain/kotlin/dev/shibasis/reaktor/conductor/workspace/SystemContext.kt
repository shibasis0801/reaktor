package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.AgentBudget
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.ToolPolicy
import kotlinx.serialization.Serializable

@Serializable
enum class ContextCategory(val label: String) {
    Architecture("Design"),
    Implementation("Build"),
    Critique("Review"),
    Verification("Test"),
    Security("Security"),
    Performance("Performance"),
}

@Serializable
enum class AgentPermission(val title: String, val summary: String, val writes: Boolean, val sandboxed: Boolean) {
    Admin("Admin", "Edits files, runs commands and uses the network without asking", true, false),
    Write("Write", "Edits files and runs commands inside the workspace sandbox", true, true),
    Read("Read", "Reads code and answers; changes nothing", false, false),
    Plan("Plan", "Reads code and drafts plans; changes no code", false, false),
}

@Serializable
enum class ReaktorAccess(val title: String, val summary: String, val denied: List<String>, val instruction: String?) {
    Full("Full", "Kernel operations, Atlas, changes and checks", emptyList(), null),
    Atlas("Atlas only", "Asks Atlas about code; no kernel operations or applied changes", listOf("mcp__reaktor__kernel", "mcp__reaktor__change_apply"),
        "Use Reaktor only through atlas_query, atlas_read and change_plan. Do not call the kernel tool or apply changes through Reaktor."),
    Off("Off", "No Reaktor tools", listOf("mcp__reaktor"), "Do not use Reaktor tools."),
}

@Serializable
data class SystemContext(
    val id: String,
    val name: String,
    val category: ContextCategory = ContextCategory.Implementation,
    val summary: String = "",
    val instructions: String = "",
    val access: TaskAccess = TaskAccess.Read,
    val tools: ToolPolicy = ToolPolicy(),
    val budget: AgentBudget? = null,
    val planKinds: Set<PlanKind> = emptySet(),
    val planRefs: List<String> = emptyList(),
    val revision: Long = 0,
    val by: BoardActor? = null,
    val updatedAt: Long = 0,
    val builtIn: Boolean = false,
    val permission: AgentPermission? = null,
    val reaktor: ReaktorAccess = ReaktorAccess.Full,
    val memory: TeamMemory? = null,
) {
    val level: AgentPermission get() = permission ?: if (access == TaskAccess.Write) AgentPermission.Admin else AgentPermission.Read

    fun withLevel(level: AgentPermission): SystemContext = copy(
        permission = level,
        access = if (level.writes) TaskAccess.Write else TaskAccess.Read,
        tools = tools.copy(allowWrites = level.writes, sandboxed = level.sandboxed),
    )

    fun composeProfile(
        profileId: String,
        profileName: String,
        runtime: RuntimeKind,
        model: String?,
        effort: String?,
        existingRevision: Long = 0,
    ): AgentProfile {
        val adaptedBudget = budget?.let { current ->
            if (runtime != RuntimeKind.ClaudeCode && current.maxCostUsd != null) {
                current.copy(maxCostUsd = null).takeIf { it.maxTokens != null || it.timeoutMillis != null }
            } else {
                current
            }
        }
        return AgentProfile(
            id = profileId,
            name = profileName,
            runtime = runtime,
            model = model?.trim()?.ifEmpty { null },
            effort = effort?.trim()?.ifEmpty { null },
            instructions = listOfNotNull(instructions.trim().ifEmpty { null }, reaktor.instruction).joinToString("\n\n"),
            access = if (level.writes) TaskAccess.Write else TaskAccess.Read,
            tools = tools.copy(allowWrites = level.writes, sandboxed = level.sandboxed, deny = (tools.deny + reaktor.denied).distinct()),
            revision = existingRevision,
            builtIn = false,
            budget = adaptedBudget,
            planKinds = planKinds,
            planRefs = planRefs,
            contextId = id,
            contextName = name,
        )
    }
}

object SystemContexts {
    private fun role(
        id: String,
        name: String,
        category: ContextCategory,
        level: AgentPermission,
        summary: String,
        instructions: String,
        planKinds: Set<PlanKind>,
        reaktor: ReaktorAccess = ReaktorAccess.Full,
        planRefs: List<String> = emptyList(),
    ) = SystemContext(id, name, category, summary, instructions, planKinds = planKinds, planRefs = planRefs, revision = 1, builtIn = true, reaktor = reaktor)
        .withLevel(level)

    val builtIns: List<SystemContext> = listOf(
        role("planner", "Planner", ContextCategory.Architecture, AgentPermission.Plan,
            "Turns a request into a plan of components, tasks and checks, and says who does each.",
            "Read the request and the code it touches through Atlas. Draw the plan with plan.write: components, tasks with acceptance, checks, and who does each. Keep tasks small enough for one agent. Change no code.",
            setOf(PlanKind.Component, PlanKind.Task, PlanKind.Check, PlanKind.Decision)),
        role("principal-architect", "Architect", ContextCategory.Architecture, AgentPermission.Admin,
            "Designs module boundaries and contracts before code is written.",
            "Map the affected code through Atlas before proposing changes. Keep framework code free of product assumptions, make illegal states unrepresentable, and split the work into tasks whose files do not overlap.",
            setOf(PlanKind.Component, PlanKind.Decision, PlanKind.Task)),
        role("atlas-cartographer", "Scout", ContextCategory.Architecture, AgentPermission.Read,
            "Maps callers, blast radius and affected checks before anyone edits.",
            "Use Atlas to list the callers, overrides and affected checks of what will change. Report a short map so builders can edit without breaking neighbours.",
            setOf(PlanKind.Component, PlanKind.Decision), ReaktorAccess.Atlas),
        role("systems-builder", "Engineer", ContextCategory.Implementation, AgentPermission.Admin,
            "Makes the change with the smallest correct diff and runs the affected checks.",
            "Read the symbols and callers you will touch, write minimal code without comments or speculative layers, keep existing contracts, and run the affected checks until they pass.",
            setOf(PlanKind.Task)),
        role("surface-composer", "UI engineer", ContextCategory.Implementation, AgentPermission.Admin,
            "Builds Hangar UI through Surface components and MachineSignal tokens.",
            "Use Surface components and MachineSignal tokens only, never literal colours or sizes. Keep test tags stable, check the layout at 1100, 1512 and 1920 wide, and show pictures of the result.",
            setOf(PlanKind.Task), planRefs = listOf("module:engine")),
        role("regulariser", "Regulariser", ContextCategory.Implementation, AgentPermission.Admin,
            "Removes needless complexity: dead code, duplicate paths and extra layers.",
            "Find code that can be deleted or merged without changing behaviour. Prefer fewer, clearer types. Run the affected checks after every removal and stop when nothing more can go.",
            setOf(PlanKind.Task)),
        role("performance-surgeon", "Optimiser", ContextCategory.Performance, AgentPermission.Admin,
            "Measures first, then removes the slow path without losing clarity.",
            "Measure before and after every change. Target frame time, recomposition, lock contention and I/O. Keep types clear and results deterministic, and report the numbers.",
            setOf(PlanKind.Task, PlanKind.Check)),
        role("verification-gate", "Tester", ContextCategory.Verification, AgentPermission.Admin,
            "Proves behaviour with flows and tests against real stores.",
            "Write Hangar flows for new behaviour and run them headless. Cover failure paths and revision conflicts. Report each result with the exact command and its output.",
            setOf(PlanKind.Check, PlanKind.Task)),
        role("adversarial-critic", "Critic", ContextCategory.Critique, AgentPermission.Read,
            "Finds real defects and names the file, line and failing input.",
            "Review independently and read-only. Every finding names a file and line and a concrete input that fails. No style remarks and no speculation.",
            setOf(PlanKind.Decision, PlanKind.Check)),
        role("judge", "Judge", ContextCategory.Critique, AgentPermission.Read,
            "Weighs proposals and findings, then records the decision.",
            "Read each proposal and its evidence. Decide, give the reason in two or three sentences, and record the decision on the plan. Write no code.",
            setOf(PlanKind.Decision)),
        role("security-auditor", "Security reviewer", ContextCategory.Security, AgentPermission.Read,
            "Checks trust boundaries, secrets and isolation.",
            "Inspect capability checks, caller identity, path handling, token storage and sandboxing. For each finding state the attacker, the entry point and the blast radius.",
            setOf(PlanKind.Decision, PlanKind.Check)),
    )

    private val byId: Map<String, SystemContext> = builtIns.associateBy { it.id }

    fun find(id: String): SystemContext? = byId[id]
}
