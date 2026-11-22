package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.ToolPolicy
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun rolesKeepTheirGroupAndDeletingOneRetiresItsFileOrRestoresTheBuiltIn() {
        val dir = Files.createTempDirectory("agent-contexts")
        try {
            val store = AgentContextStore(dir, clock = { 42L })
            assertEquals("Research", store.read("researcher").shelf)
            assertEquals("Build", store.read("systems-builder").shelf)
            val mine = store.save(SystemContext("release-checker", "Release checker", group = "  Shipping  ",
                tools = ToolPolicy(deny = listOf("WebFetch"), additionalDirectories = listOf("~/dev/reaktor"))), person)
            assertEquals("Shipping", mine.shelf)
            assertEquals(listOf("WebFetch"), store.read("release-checker").tools.deny)
            assertEquals(listOf("~/dev/reaktor"), store.read("release-checker").tools.additionalDirectories)
            val atlas = mine.copy(reaktor = ReaktorAccess.Atlas)
            val codex = atlas.composeProfile("codex-checker", "Checker", RuntimeKind.Codex, "gpt-6.1-sol", "xhigh")
            assertTrue(codex.tools.deny.isEmpty())
            assertTrue(codex.instructions.endsWith("Do not use these tools: WebFetch."))
            assertEquals(listOf("WebFetch", "mcp__reaktor__kernel", "mcp__reaktor__change_apply"),
                atlas.composeProfile("claude-checker", "Checker", RuntimeKind.ClaudeCode, "claude-opus-5-5", "max").tools.deny)
            assertTrue(ConfigHarness.effects(atlas).single { it.runtime == RuntimeKind.Codex }.flags.contains("--add-dir ~/dev/reaktor"))
            assertFailsWith<IllegalArgumentException> { store.delete("release-checker", person, expectedRevision = 2) }
            assertNull(store.delete("release-checker", person, expectedRevision = 1))
            assertTrue(store.list().none { it.id == "release-checker" })
            assertTrue(Files.exists(dir.resolve("retired/release-checker-42.json")))
            val tester = store.save(store.read("verification-gate").copy(summary = "Proves releases"), person)
            assertEquals("verification-gate", store.delete("verification-gate", person, tester.revision)?.id)
            assertTrue(store.read("verification-gate").builtIn)
            assertFailsWith<IllegalArgumentException> { store.delete("verification-gate", person, expectedRevision = 1) }
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
    fun variantsAttachAnyAgentToATopologyAndTellEachNodeWhereItSits() {
        val contexts = SystemContexts.builtIns.associateBy { it.id }
        fun agent(id: String, role: String, model: String) = contexts.getValue(role).composeProfile(id, id, RuntimeKind.ClaudeCode, model, "high")
        val saved = listOf(agent("architect-opus", "principal-architect", "claude-opus-5-5"), agent("engineer-sonnet", "systems-builder", "claude-sonnet-5-5"),
            agent("judge-sonnet", "judge", "claude-sonnet-5-5")).associateBy { it.id }
        val added = TeamRules.apply(AgentTeam(), TeamPatch(listOf(
            TeamOp.SaveVariant("council", "fan-in", "Council", prompt = "Decide how the plan store keeps history."),
            TeamOp.Attach("council", "branch-1", "architect-opus"), TeamOp.Attach("council", "branch-2", "engineer-sonnet"),
            TeamOp.Attach("council", "join", "judge-sonnet"), TeamOp.NodePrompt("council", "branch-1", "Favour the smallest change."),
            TeamOp.Attach("council", "branch-3", model = "claude-opus-5-5:max"),
        )), person, 1_000L, saved = saved::containsKey)
        assertTrue(added.refused.isEmpty(), "${added.refused}")
        val team = added.team
        val council = assertNotNull(team.variant("council"))
        assertEquals("fan-in", council.topology)
        assertEquals(mapOf("branch-1" to "architect-opus", "branch-2" to "engineer-sonnet", "join" to "judge-sonnet"), council.agents)
        assertEquals(listOf(council to "branch-2"), team.attached("engineer-sonnet"))
        assertEquals(mapOf("branch-3" to "claude-opus-5-5:max"), council.models)
        assertEquals("Opus 5.5 · max", council.model("branch-3")?.label)

        val refused = TeamRules.apply(team, TeamPatch(listOf(TeamOp.Attach("council", "branch-3", "ghost"), TeamOp.Attach("council", "chair", "judge-sonnet"),
            TeamOp.SaveVariant("other", "no-such-topology"), TeamOp.SaveVariant("council", "pair"), TeamOp.Attach("council", "branch-3", "judge-sonnet", "claude-opus-5-5:max"),
            TeamOp.Attach("council", "branch-3", model = "no-such-model:max"))), person, 2_000L, saved = saved::containsKey)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), refused.refused.map { it.op })

        val briefs = team.briefs(council, saved::get)
        val join = briefs.getValue("join")
        assertEquals(saved.getValue("judge-sonnet").instructions, join.agent)
        assertEquals("You are Join in “Council”, which follows the Fan-in topology (4 nodes). " +
            "You receive work from Branch 1 (architect-opus), Branch 2 (engineer-sonnet) and Branch 3 (Opus 5.5 · max).", join.position)
        assertTrue(join.text.endsWith("Decide how the plan store keeps history."))
        val branch = briefs.getValue("branch-1")
        assertEquals("Favour the smallest change.", branch.node)
        assertEquals("You are Branch 1 in “Council”, which follows the Fan-in topology (4 nodes). You hand your output to Join (judge-sonnet).", branch.position)
        assertEquals(listOf(branch.agent, branch.node, branch.position, branch.shared).joinToString("\n\n"), branch.text)
        val bare = briefs.getValue("branch-3")
        assertEquals("", bare.agent)
        assertEquals(bare.position + "\n\nDecide how the plan store keeps history.", bare.text)

        val shape = team.shape(saved::get)
        assertNotNull(shape.scopes.firstOrNull { it.id == "team variant council" })
        val nodes = shape.nodes.filter { it.attributes["variant"] == "council" }
        assertEquals(setOf("council/branch-1", "council/branch-2", "council/branch-3", "council/join"), nodes.mapNotNull { it.attributes["node"] }.toSet())
        assertEquals("judge-sonnet", nodes.first { it.attributes["node"] == "council/join" }.attributes["agent"])
        assertEquals("true", nodes.first { it.attributes["node"] == "council/branch-1" }.attributes["prompted"])
        assertEquals("claude-opus-5-5:max", nodes.first { it.attributes["node"] == "council/branch-3" }.attributes["pick"])
        val joinId = nodes.first { it.attributes["node"] == "council/join" }.id
        assertEquals(setOf("Branch 1", "Branch 2", "Branch 3"), shape.wires.filter { it.consumer == joinId }.map { it.consumerPort }.toSet())

        val cleared = TeamRules.apply(team, TeamPatch(listOf(TeamOp.Attach("council", "branch-2"), TeamOp.Attach("council", "branch-3"), TeamOp.NodePrompt("council", "branch-1", ""),
            TeamOp.SaveVariant("council", "fan-in", "Council", prompt = "")), base = team.revision), person, 3_000L).team.variant("council")
        assertEquals(mapOf("branch-1" to "architect-opus", "join" to "judge-sonnet"), cleared?.agents)
        assertTrue(cleared?.nodePrompts.orEmpty().isEmpty())
        assertTrue(cleared?.models.orEmpty().isEmpty())
        assertEquals("", cleared?.prompt)
        val removed = TeamRules.apply(team, TeamPatch(listOf(TeamOp.RemoveVariant("council")), base = team.revision), person, 4_000L).team
        assertTrue(removed.variants.isEmpty())
    }

    @Test
    fun ownTopologiesCopyABuiltInAndKeepTheirVariants() {
        val fanIn = assertNotNull(Topologies.builtIn("fan-in"))
        val wide = fanIn.duplicate("branch-3").copy(id = "fan-in-4", title = "Fan-in of four")
        assertEquals(listOf("branch-1", "branch-2", "branch-3", "join", "branch-4"), wide.nodes.map { it.key })
        assertEquals("Branch 4", wide.name("branch-4"))
        assertEquals(TopologyEdge("branch-4", "join"), wide.edges.last())
        val saved = setOf("a", "b", "c", "d")
        val added = TeamRules.apply(AgentTeam(), TeamPatch(listOf(TeamOp.SaveTopology(wide), TeamOp.SaveVariant("best", wide.id, "Best of four"),
            TeamOp.Attach("best", "branch-4", "d"), TeamOp.SaveVariant("best-2", wide.id))), person, 1_000L, saved = saved::contains)
        assertTrue(added.refused.isEmpty(), "${added.refused}")
        val team = added.team
        assertEquals(listOf(wide), team.topologies)
        assertEquals(Topologies.builtIns.size + 1, team.library.size)
        assertEquals("Fan-in of four", team.variant("best-2")?.title)
        assertEquals(listOf("best", "best-2"), team.variantsOf(wide.id).map { it.key })

        val refused = TeamRules.apply(team, TeamPatch(listOf(
            TeamOp.SaveTopology(fanIn),
            TeamOp.SaveTopology(wide.copy(id = "Bad Id")),
            TeamOp.SaveTopology(wide.copy(id = "empty", nodes = emptyList(), edges = emptyList())),
            TeamOp.SaveTopology(wide.copy(id = "loose", edges = listOf(TopologyEdge("join", "ghost")))),
            TeamOp.SaveTopology(wide.copy(id = "twins", nodes = wide.nodes + wide.nodes.first())),
            TeamOp.SaveTopology(wide.copy(id = "doubled", edges = wide.edges + wide.edges.first().copy(label = "again"))),
            TeamOp.RemoveTopology(wide.id),
            TeamOp.RemoveTopology("fan-in"),
        )), person, 2_000L)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), refused.refused.map { it.op })
        assertTrue(refused.refused[6].reason.startsWith("Remove its 2 variants first"), refused.refused[6].reason)

        val trimmed = wide.without("branch-4")
        val changed = TeamRules.apply(team, TeamPatch(listOf(TeamOp.SaveTopology(trimmed)), base = team.revision), person, 3_000L)
        assertEquals("Changed the topology fan-in-4 “Fan-in of four”; cleared removed nodes in Best of four", changed.applied.single())
        assertTrue(changed.team.variant("best")?.agents.orEmpty().isEmpty())

        val graph = team.topologiesShape()
        assertEquals("Fan-in of four", graph.scopes.first { it.id == "topology fan-in-4" }.label)
        val own = graph.nodes.filter { it.attributes["topology"] == wide.id }
        assertTrue(own.all { it.attributes["variants"] == "2" && it.attributes["custom"] == "true" })
        assertTrue(graph.nodes.filter { it.attributes["topology"] == "fan-in" }.none { "custom" in it.attributes })
        assertEquals(Topologies.builtIns.sumOf { it.nodes.size } + wide.nodes.size, graph.nodes.size)
    }

    @Test
    fun legacyTeamFilesMoveToTopologiesAndVariants() {
        val dir = Files.createTempDirectory("agent-team-legacy")
        try {
            val legacy = """{"revision":31,"members":[{"profile":"planner-opus"}],"structures":[
                {"key":"council","template":"council","title":"Council","seats":{"member-a":"planner-opus","chair":"planner-fable"},"revision":20},
                {"key":"pair-2","template":"pair","title":"Pair 2","revision":21},
                {"key":"peo","template":"planner-engineer-optimiser","title":"Opus · Sol · Carbon","seats":{"planner":"planner-opus","engineer":"engineer-sol"}}],
                "templates":[{"id":"planner-engineer-optimiser","title":"Planner · Engineer · Optimiser","summary":"Build it, make it fast.",
                "seats":[{"key":"planner","role":"Planner","duty":"Plans","tier":"Frontier","config":"planner"},{"key":"engineer","role":"Engineer","duty":"Builds","tier":"Strong","config":"systems-builder"}],
                "links":[{"from":"planner","to":"engineer","label":"plan"}]}]}"""
            Files.writeString(dir.resolve("team.json"), legacy)
            val team = AgentTeamStore(dir).team()
            assertEquals(31L, team.revision)
            assertEquals(listOf("planner-opus"), team.members.map { it.profile })
            assertEquals(listOf("council", "peo"), team.variants.map { it.key })
            assertEquals(TeamVariant("council", "fan-in", "Council", agents = mapOf("branch-1" to "planner-opus", "join" to "planner-fable"), revision = 20), team.variant("council"))
            assertEquals(mapOf("planner" to "planner-opus", "engineer" to "engineer-sol"), team.variant("peo")?.agents)
            val own = team.topologies.single()
            assertEquals(listOf(TopologyNode("planner", "Planner"), TopologyNode("engineer", "Engineer")), own.nodes)
            assertEquals(listOf(TopologyEdge("planner", "engineer", "plan")), own.edges)
            assertEquals(legacy, Files.readString(dir.resolve("team-legacy.json")))
            assertEquals(team, AgentTeamStore(dir).team())
        } finally {
            dir.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")).canonicalFile)
        }
    }
}
