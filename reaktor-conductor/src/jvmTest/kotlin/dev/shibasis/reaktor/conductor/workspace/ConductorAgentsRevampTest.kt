package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConductorAgentsRevampTest {
    private val person = BoardActor(BoardActorKind.Person, "owner (Hangar)")

    @Test
    fun systemContextsPersistAndComposeOrthogonalAgentProfiles() {
        val dir = Files.createTempDirectory("agent-contexts")
        try {
            val store = AgentContextStore(dir, clock = { 42L })
            val initial = store.list()
            assertTrue(initial.any { it.id == "principal-architect" && it.builtIn })

            val custom = SystemContext(
                id = "rust-ffi-specialist",
                name = "Rust FFI Specialist",
                category = ContextCategory.Implementation,
                summary = "Zero-copy JNI/Panama bridges and memory safety checks",
                instructions = "Keep C ABI boundaries minimal and verify ownership invariants.",
                access = TaskAccess.Write,
                budget = dev.shibasis.reaktor.conductor.AgentBudget(maxTokens = 120_000, maxCostUsd = 2.5),
                planKinds = setOf(PlanKind.Task, PlanKind.Check),
            )
            val saved = store.save(custom, person)
            assertEquals(1L, saved.revision)
            assertFalse(saved.builtIn)

            val shipped = initial.first { it.id == "verification-gate" }
            val edited = store.save(shipped.withLevel(AgentPermission.Write).copy(reaktor = ReaktorAccess.Atlas), person)
            assertEquals(1L, edited.revision)
            assertEquals(listOf(edited), store.list().filter { it.id == "verification-gate" })
            assertEquals(AgentPermission.Write, store.read("verification-gate").level)
            assertEquals(2L, store.save(edited.copy(summary = "Proves releases"), person, expectedRevision = 1L).revision)

            val codexComposed = saved.composeProfile("codex-ffi", "Codex FFI Builder", RuntimeKind.Codex, "gpt-6.1-sol", "xhigh")
            assertEquals("rust-ffi-specialist", codexComposed.contextId)
            assertEquals("Rust FFI Specialist", codexComposed.contextName)
            assertNull(codexComposed.budget?.maxCostUsd)
            assertEquals(120_000L, codexComposed.budget?.maxTokens)

            val claudeComposed = saved.composeProfile("claude-ffi", "Claude FFI Architect", RuntimeKind.ClaudeCode, "claude-opus-5-5", "max")
            assertEquals(2.5, claudeComposed.budget?.maxCostUsd)
        } finally {
            dir.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")).canonicalFile)
        }
    }

    @Test
    fun hierarchicalTeamGroupsProjectNestedScopesPreventCyclesAndReparentCleanly() {
        val roles = mapOf(
            "org-principal-architect" to "principal-architect",
            "org-systems-lead" to "systems-builder",
            "org-core-builder" to "systems-builder",
            "org-surface-engineer" to "surface-composer",
            "org-invariant-critic" to "adversarial-critic",
            "org-gate-verifier" to "verification-gate",
        )
        val contexts = SystemContexts.builtIns.associateBy { it.id }
        val profiles = roles.mapValues { (id, context) -> contexts.getValue(context).composeProfile(id, id, RuntimeKind.ClaudeCode, "claude-opus-5-5", "high") }
        val ops = roles.keys.map { TeamOp.AddMember(it) } + listOf(
            TeamOp.Group("eng-division", "Engineering Division", emptyList()),
            TeamOp.Group("arch-office", "Architecture & Spec", listOf("org-principal-architect"), parent = "eng-division"),
            TeamOp.Group("delivery-wing", "Delivery Squads", emptyList(), parent = "eng-division"),
            TeamOp.Group("core-squad", "Core Systems Squad", listOf("org-systems-lead", "org-core-builder"), parent = "delivery-wing"),
            TeamOp.Group("surface-squad", "Surface & Graph Squad", listOf("org-surface-engineer"), parent = "delivery-wing"),
            TeamOp.Group("assurance-council", "Assurance & Gate Council", listOf("org-invariant-critic", "org-gate-verifier"), parent = "eng-division"),
        )
        val applied = TeamRules.apply(AgentTeam(), TeamPatch(ops), person, 1_000L, saved = profiles::containsKey)
        assertTrue(applied.refused.isEmpty(), "${applied.refused}")
        val team = applied.team
        assertTrue(team.hasHierarchy)
        assertEquals("Engineering Division › Delivery Squads › Core Systems Squad", team.groupPathLabel("core-squad"))
        assertEquals(6, team.allMembersOf("eng-division").size)

        val cycleAttempt = TeamRules.apply(
            team,
            TeamPatch(listOf(TeamOp.Group("eng-division", "Engineering Division", emptyList(), parent = "core-squad")), base = team.revision),
            person,
            2_000L,
        )
        assertEquals(1, cycleAttempt.refused.size)
        assertTrue("cycle" in cycleAttempt.refused.single().reason)

        val shape = team.shape(profiles::get)
        val coreScope = assertNotNull(shape.scopes.firstOrNull { it.id == "team group core-squad" })
        val deliveryScope = assertNotNull(shape.scopes.firstOrNull { it.id == "team group delivery-wing" })
        val divisionScope = assertNotNull(shape.scopes.firstOrNull { it.id == "team group eng-division" })
        assertEquals("team group delivery-wing", coreScope.parent)
        assertEquals("team group eng-division", deliveryScope.parent)
        assertEquals("team", divisionScope.parent)

        val lifted = TeamRules.apply(
            team,
            TeamPatch(listOf(TeamOp.Ungroup("delivery-wing")), base = team.revision),
            person,
            3_000L,
        ).team
        assertEquals("eng-division", lifted.group("core-squad")?.parent)
        assertEquals("eng-division", lifted.group("surface-squad")?.parent)
    }

    @Test
    fun structuresSeatSavedProfilesAndDrawSeatsOnTheTeamGraph() {
        val engineer = SystemContexts.builtIns.first { it.id == "systems-builder" }
            .composeProfile("engineer", "Engineer · Sonnet", RuntimeKind.ClaudeCode, "claude-sonnet-5-5", "high")
        val saved = mapOf(engineer.id to engineer)
        val added = TeamRules.apply(AgentTeam(), TeamPatch(listOf(TeamOp.Structure("pet", "planner-engineer-tester"), TeamOp.Seat("pet", "engineer", "engineer"))),
            person, 1_000L, saved = saved::containsKey)
        assertTrue(added.refused.isEmpty(), "${added.refused}")
        val structure = assertNotNull(added.team.structure("pet"))
        assertEquals("Planner · Engineer · Tester", structure.title)
        assertEquals(mapOf("engineer" to "engineer"), structure.seats)
        assertEquals(listOf(structure to "engineer"), added.team.seated("engineer"))

        val refused = TeamRules.apply(added.team, TeamPatch(listOf(TeamOp.Seat("pet", "tester", "ghost"), TeamOp.Seat("pet", "chair", "engineer"),
            TeamOp.Structure("other", "no-such-template"))), person, 2_000L, saved = saved::containsKey)
        assertEquals(3, refused.refused.size)

        val shape = added.team.shape(saved::get)
        assertNotNull(shape.scopes.firstOrNull { it.id == "team structure pet" })
        val seats = shape.nodes.filter { it.attributes["structure"] == "pet" }
        assertEquals(setOf("pet/planner", "pet/engineer", "pet/tester"), seats.mapNotNull { it.attributes["seat"] }.toSet())
        assertEquals("Engineer · Sonnet", seats.first { it.attributes["seat"] == "pet/engineer" }.label)
        assertEquals("Tester", seats.first { it.attributes["seat"] == "pet/tester" }.label)
        val seatIds = seats.mapTo(HashSet()) { it.id }
        assertEquals(setOf("plan", "change", "failures", "result"), shape.wires.filter { it.consumer in seatIds }.map { StructureSeatNode.label(it.consumerPort) }.toSet())
        fun seatOf(node: String): String? = seats.firstOrNull { it.id == node }?.attributes?.get("seat")
        val handOffs = shape.wires.filter { it.consumer in seatIds }.map { Triple(seatOf(it.provider), StructureSeatNode.label(it.consumerPort), seatOf(it.consumer)) }.toSet()
        assertEquals(setOf(Triple("pet/planner", "plan", "pet/engineer"), Triple("pet/engineer", "change", "pet/tester"),
            Triple("pet/tester", "failures", "pet/engineer"), Triple("pet/tester", "result", "pet/planner")), handOffs)
        val workers = TeamRules.apply(AgentTeam(), TeamPatch(listOf(TeamOp.Structure("crew", "lead-workers"))), person, 1_000L).team.shape()
        assertEquals(setOf("result · worker-a", "result · worker-b", "result · worker-c", "task"),
            workers.wires.filter { wire -> workers.nodes.any { it.id == wire.consumer && it.attributes["seat"] == "crew/lead" } || wire.consumerPort == "task" }.map { it.consumerPort }.toSet())

        val emptied = TeamRules.apply(added.team, TeamPatch(listOf(TeamOp.Seat("pet", "engineer")), base = added.team.revision), person, 3_000L).team
        assertTrue(emptied.structure("pet")?.seats.orEmpty().isEmpty())
        val removed = TeamRules.apply(emptied, TeamPatch(listOf(TeamOp.Unstructure("pet")), base = emptied.revision), person, 4_000L).team
        assertTrue(removed.structures.isEmpty())
    }
}
