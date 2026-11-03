package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentTeamTest {
    private val person = BoardActor(BoardActorKind.Person, "owner (Hangar)")
    private val lead = BoardActor(BoardActorKind.Agent, "blueprint lead", "claude:lead")
    private val critic = BoardActor(BoardActorKind.Agent, "agents lead", "claude:critic")
    private val six = listOf("bpo-lead", "bpo-agents-lead", "bpo-engine", "bpo-cartographer", "bpo-designer", "bpo-auditor")

    private fun change(team: AgentTeam, by: BoardActor, vararg ops: TeamOp, base: Long? = team.revision, at: Long = 100,
        busy: (String) -> String? = { null }) = TeamRules.apply(team, TeamPatch(ops.toList(), base), by, at, busy = busy)

    private fun seeded(): AgentTeam {
        val members = change(AgentTeam(), lead, *six.map { TeamOp.AddMember(it) }.toTypedArray()).team
        return change(members, lead, *six.drop(1).map { TeamOp.Relate("bpo-lead", it, TeamLinkKind.Supervises) }.toTypedArray()).team
    }

    @Test fun everyOpAppliesAndRecordsWhoMadeItAndWhen() {
        val team = seeded()
        assertEquals(six, team.active.map { it.profile })
        assertEquals(5, team.links.count { it.kind == TeamLinkKind.Supervises })

        val shaped = change(team, critic,
            TeamOp.AddMember("bpo-engine", TeamMemory.Elephant),
            TeamOp.AddMember("bpo-designer", TeamMemory.Goldfish),
            TeamOp.Relate("bpo-engine", "bpo-designer", TeamLinkKind.PairsWith),
            TeamOp.Relate("bpo-agents-lead", "bpo-engine", TeamLinkKind.CriticOf),
            TeamOp.Group("council", "Layout council", listOf("bpo-engine", "bpo-cartographer", "bpo-designer")),
            at = 200,
        )
        assertEquals(emptyList(), shaped.refused)
        assertEquals(5, shaped.applied.size)
        val after = shaped.team
        assertEquals(team.revision + 1, after.revision)
        assertEquals(TeamMemory.Elephant, after.member("bpo-engine")?.memory)
        assertEquals(critic, after.member("bpo-designer")?.by)
        assertEquals(200, after.member("bpo-designer")?.changedAt)
        assertEquals(listOf("bpo-engine", "bpo-cartographer", "bpo-designer"), after.groupOf("bpo-cartographer")?.members)
        val change = after.changes.last()
        assertEquals(critic, change.by)
        assertEquals(200, change.at)
        assertTrue(change.summary.any { "critic of" in it }, "${change.summary}")

        val unchanged = change(after, critic, TeamOp.Relate("bpo-designer", "bpo-engine", TeamLinkKind.PairsWith))
        assertEquals(after, unchanged.team)
        assertEquals(emptyList(), unchanged.applied)

        val moved = change(after, lead, TeamOp.Group("ops", "Ops lane", listOf("bpo-auditor", "bpo-designer")),
            TeamOp.Unrelate("bpo-agents-lead", "bpo-engine", TeamLinkKind.CriticOf)).team
        assertEquals(listOf("bpo-engine", "bpo-cartographer"), moved.groupOf("bpo-engine")?.members)
        assertEquals("ops", moved.groupOf("bpo-designer")?.key)
        assertTrue(moved.links.none { it.kind == TeamLinkKind.CriticOf })

        val ungrouped = change(moved, lead, TeamOp.Ungroup("ops")).team
        assertNull(ungrouped.groupOf("bpo-designer"))
        assertEquals(listOf("council"), ungrouped.groups.map { it.key })
    }

    @Test fun aStaleBaseRefusesOnlyTheOpsItTouches() {
        val team = seeded()
        val read = team.revision
        val changed = change(team, critic, TeamOp.Group("council", "Council", listOf("bpo-engine", "bpo-designer"))).team

        val stale = change(changed, lead, TeamOp.Ungroup("council"), TeamOp.Unrelate("bpo-lead", "bpo-engine", TeamLinkKind.Supervises), base = read)
        assertEquals(listOf("council"), stale.refused.map { it.item })
        assertTrue("read the team again" in stale.refused.single().reason)
        assertNotNull(stale.team.groupOf("bpo-engine"))
        assertTrue(stale.team.links.none { it.from == "bpo-lead" && it.to == "bpo-engine" })
        assertEquals(listOf(changed.changes.last()), stale.news)

        val own = change(changed, critic, TeamOp.Ungroup("council"), base = read)
        assertEquals(emptyList(), own.refused)
    }

    @Test fun onlyAPersonRetiresAnIdleMemberAtTheCurrentRevision() {
        val team = change(seeded(), lead, TeamOp.Group("council", "Council", listOf("bpo-auditor"))).team

        assertTrue("Only a person" in change(team, lead, TeamOp.RetireMember("bpo-auditor", "Lane closed")).refused.single().reason)
        assertTrue("current team" in change(team, person, TeamOp.RetireMember("bpo-auditor", "Lane closed"), base = team.revision - 1).refused.single().reason)
        assertTrue("reason" in change(team, person, TeamOp.RetireMember("bpo-auditor", " ")).refused.single().reason)
        val busy = change(team, person, TeamOp.RetireMember("bpo-auditor", "Lane closed"), busy = { "$it is doing hangar:blueprint-one#acc-pane-performance" })
        assertTrue("acc-pane-performance" in busy.refused.single().reason)
        assertEquals(team, busy.team)

        val retired = change(team, person, TeamOp.RetireMember("bpo-auditor", "Lane closed")).team
        assertNull(retired.member("bpo-auditor"))
        assertEquals("Lane closed", retired.members.single { it.profile == "bpo-auditor" }.retired?.reason)
        assertTrue(retired.links.none { it.to == "bpo-auditor" })
        assertTrue(retired.groups.isEmpty())
        assertTrue("not on the team" in change(retired, lead, TeamOp.Relate("bpo-lead", "bpo-auditor", TeamLinkKind.Supervises)).refused.single().reason)

        val back = change(retired, lead, TeamOp.AddMember("bpo-auditor", TeamMemory.Goldfish)).team
        assertEquals(TeamMemory.Goldfish, back.member("bpo-auditor")?.memory)
    }

    @Test fun supervisionStaysAHierarchy() {
        val team = change(seeded(), lead, TeamOp.Relate("bpo-engine", "bpo-designer", TeamLinkKind.Supervises)).team
        val cycle = change(team, lead, TeamOp.Relate("bpo-designer", "bpo-lead", TeamLinkKind.Supervises))
        assertTrue("cycle" in cycle.refused.single().reason)
        assertTrue("itself" in change(team, lead, TeamOp.Relate("bpo-engine", "bpo-engine", TeamLinkKind.CriticOf)).refused.single().reason)
        assertTrue("not a saved profile" in TeamRules.apply(team, TeamPatch(listOf(TeamOp.AddMember("ghost"))), lead, 1, saved = { false }).refused.single().reason)
        assertEquals(emptyList(), change(team, lead, TeamOp.Relate("bpo-designer", "bpo-lead", TeamLinkKind.CriticOf)).refused)
    }

    @Test fun theShapeHasANodePerMemberAGraphPerGroupAndAWirePerLink() {
        val team = change(seeded(), lead,
            TeamOp.Group("council", "Layout council", listOf("bpo-engine", "bpo-designer")),
            TeamOp.Relate("bpo-agents-lead", "bpo-engine", TeamLinkKind.CriticOf),
            TeamOp.AddMember("bpo-engine", TeamMemory.Elephant),
        ).team
        val profiles = mapOf("bpo-engine" to AgentProfile("bpo-engine", "engine", RuntimeKind.Codex, "gpt-6.1-sol", "xhigh"))
        val shape = team.shape(profiles::get)

        assertEquals(six.map { "team member $it" }.toSet(), shape.nodes.map { it.id }.toSet())
        assertTrue(shape.nodes.all { it.kind == NodeKind.Actor })
        assertEquals(setOf("team", "team group council"), shape.scopes.map { it.id }.toSet())
        assertEquals("Layout council", shape.scopes.single { it.id == "team group council" }.label)
        assertEquals("team group council", shape.nodes.single { it.id == "team member bpo-engine" }.scope)

        val engine = shape.nodes.single { it.id == "team member bpo-engine" }
        assertEquals("engine", engine.label)
        assertEquals("(codex, xhigh, sol 6.1, engine)", engine.attributes["tuple"])
        assertEquals("elephant", engine.attributes["memory"])
        assertEquals("council", engine.attributes["group"])

        assertEquals(team.links.size, shape.wires.size)
        assertEquals(5, shape.wires.count { it.consumer == "team member bpo-lead" && it.consumerPort.startsWith("supervises ") })
        assertTrue(shape.wires.any { it.consumer == "team member bpo-agents-lead" && it.consumerPort == "critic of" && it.provider == "team member bpo-engine" }, "${shape.wires}")
    }

    @Test fun theStoreKeepsTheTeamOnDiskAndAnswersNews() {
        val directory = Files.createTempDirectory("agent-team")
        try {
            val store = AgentTeamStore(directory, clock = { 7 })
            val first = store.write(TeamPatch(six.map { TeamOp.AddMember(it) }), lead)
            store.write(TeamPatch(listOf(TeamOp.Relate("bpo-lead", "bpo-engine", TeamLinkKind.Supervises)), base = first.team.revision), critic)
            val reopened = AgentTeamStore(directory)
            assertEquals(store.team(), reopened.team())
            assertEquals(2, reopened.team().revision)
            assertEquals(listOf(2L), reopened.read(since = 1, reader = lead).news.map { it.revision })
            assertEquals(emptyList(), reopened.read(since = 1, reader = critic).news)
        } finally {
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")).canonicalFile)
        }
    }

    @Test fun opsAcceptRelationAndMemoryWordsAsPeopleWriteThem() {
        val ops = TeamJson.ops(JsonArray(listOf(
            buildJsonObject { put("type", "relate"); put("from", "a"); put("to", "b"); put("kind", "critic of") },
            buildJsonObject { put("type", "relate"); put("from", "a"); put("to", "b"); put("kind", "pairs-with") },
            buildJsonObject { put("type", "add-member"); put("profile", "a"); put("memory", "elephant") },
        )))
        val decoded = AgentWorkspaceJson.decodeFromJsonElement(ListSerializer(TeamOp.serializer()), ops)
        assertEquals(listOf<TeamOp>(TeamOp.Relate("a", "b", TeamLinkKind.CriticOf), TeamOp.Relate("a", "b", TeamLinkKind.PairsWith),
            TeamOp.AddMember("a", TeamMemory.Elephant)), decoded)
        assertEquals(JsonPrimitive("Supervises"), (TeamJson.ops(JsonArray(listOf(buildJsonObject { put("kind", "SUPERVISES") }))) as JsonArray)[0].let { (it as JsonObject)["kind"] })
    }
}
