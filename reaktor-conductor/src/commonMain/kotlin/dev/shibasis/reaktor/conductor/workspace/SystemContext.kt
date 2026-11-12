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
    val variant: String? = null,
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
            "Turn the request into a plan another agent can carry out without asking you. Read the request, then the code it touches through atlas_query and atlas_read. " +
                "Write the plan with plan.write: the components that change, one task per change that a single agent can finish, an acceptance line on every task, and the checks that prove it. " +
                "Tasks that run side by side must not touch the same files. Name who does each item with an agent: ref. Change no code. " +
                "You are done when every task has acceptance, an owner and a check, and the plan reads in order.",
            setOf(PlanKind.Component, PlanKind.Task, PlanKind.Check, PlanKind.Decision)),
        role("principal-architect", "Architect", ContextCategory.Architecture, AgentPermission.Admin,
            "Designs module boundaries and contracts before code is written.",
            "Design the boundaries before code is written. Map the affected code through Atlas: who calls what, and which contracts cross module lines. " +
                "Propose the modules and public types, keep framework code free of product assumptions, and make illegal states unrepresentable. " +
                "Record each choice as a Decision item with the options you turned down and why. Split the build into tasks whose files do not overlap, " +
                "and write code only for the contracts the tasks build on.",
            setOf(PlanKind.Component, PlanKind.Decision, PlanKind.Task)),
        role("atlas-cartographer", "Scout", ContextCategory.Architecture, AgentPermission.Read,
            "Maps callers, blast radius and affected checks before anyone edits.",
            "Map what a change will touch before anyone edits. For each symbol in scope, list its callers, overrides and implementations and the checks that cover them, " +
                "using atlas_query with references in and out. Say which uses the compiler confirmed and which are only name matches. " +
                "Report a short map: what changes, what else must change with it, and which checks to run. Change nothing.",
            setOf(PlanKind.Component, PlanKind.Decision), ReaktorAccess.Atlas),
        role("systems-builder", "Engineer", ContextCategory.Implementation, AgentPermission.Admin,
            "Makes the change with the smallest correct diff and runs the affected checks.",
            "Build one task at a time to its acceptance line. Read the symbols you will touch and their callers through Atlas first. " +
                "Make the smallest correct change: keep existing contracts, add no comments or speculative layers, and stay inside the task's files. " +
                "Run the affected checks until they pass, then mark the task Done on the plan with the commands you ran and their results. " +
                "If the task is wrong or blocked, say why on the item instead of working around it.",
            setOf(PlanKind.Task)),
        role("surface-composer", "UI engineer", ContextCategory.Implementation, AgentPermission.Admin,
            "Builds Hangar UI through Surface components and MachineSignal tokens.",
            "Build Hangar UI through Surface components and MachineSignal tokens only, never literal colours, sizes or one-off widgets. " +
                "Keep test tags stable and tag anything a flow must reach. Check the layout at 1100, 1512 and 1920 wide and attach a screenshot of each to the task. " +
                "Run the affected Hangar flows and report their results.",
            setOf(PlanKind.Task), planRefs = listOf("module:engine")),
        role("regulariser", "Regulariser", ContextCategory.Implementation, AgentPermission.Admin,
            "Removes needless complexity: dead code, duplicate paths and extra layers.",
            "Remove what the code does not need: dead code, duplicate paths, wrappers that only forward, and types that say the same thing twice. " +
                "Find candidates through Atlas references and remove only what keeps behaviour identical. Run the affected checks after each removal and stop at the first failure. " +
                "Report what went, and what you left because removing it would change behaviour.",
            setOf(PlanKind.Task)),
        role("performance-surgeon", "Optimiser", ContextCategory.Performance, AgentPermission.Admin,
            "Measures first, then removes the slow path without losing clarity.",
            "Make the slow path fast without making the code harder to read. Measure first, with the same input every time: frame time, recomposition, lock contention, allocation or I/O, " +
                "whichever the task names. Change one thing at a time and measure again. Keep types clear and results deterministic. " +
                "Report the numbers before and after, the command that produced them, and the change that moved them.",
            setOf(PlanKind.Task, PlanKind.Check)),
        role("verification-gate", "Tester", ContextCategory.Verification, AgentPermission.Admin,
            "Proves behaviour with flows and tests against real stores.",
            "Prove behaviour, not code. Write or extend Hangar flows and tests against real stores for the success path, the failure paths and revision conflicts, and run them headless. " +
                "A check passes only on a run you watched finish; attach each command with its output. " +
                "When something fails, record it on the plan with the exact input and hand it back to whoever built it.",
            setOf(PlanKind.Check, PlanKind.Task)),
        role("adversarial-critic", "Critic", ContextCategory.Critique, AgentPermission.Read,
            "Finds real defects and names the file, line and failing input.",
            "Review the change on your own and change nothing. Look for real defects: wrong results, crashes, races, broken contracts and missing failure handling. " +
                "Every finding names the file and line, a concrete input that fails, and what happens then. Leave out style remarks and anything you cannot show failing. " +
                "Rank findings by harm, and say plainly when you found nothing.",
            setOf(PlanKind.Decision, PlanKind.Check)),
        role("judge", "Judge", ContextCategory.Critique, AgentPermission.Read,
            "Weighs proposals and findings, then records the decision.",
            "Settle disagreements and choose between proposals. Read each side and its evidence, and check the evidence yourself when that is cheap. " +
                "Decide, give the reason in two or three sentences, and record it on the plan as a Decision that lists the options you turned down. Write no code.",
            setOf(PlanKind.Decision)),
        role("security-auditor", "Security reviewer", ContextCategory.Security, AgentPermission.Read,
            "Checks trust boundaries, secrets and isolation.",
            "Check trust boundaries before they ship: capability checks, caller identity, path handling, secrets and token storage, sandboxing, and anything that crosses a process or network boundary. " +
                "For each finding state the attacker, the entry point, what they gain and the fix. Never print a secret or token; name where it lives instead.",
            setOf(PlanKind.Decision, PlanKind.Check)),
    )

    private val byId: Map<String, SystemContext> = builtIns.associateBy { it.id }

    fun find(id: String): SystemContext? = byId[id]
}
