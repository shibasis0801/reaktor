package dev.shibasis.reaktor.blueprint.canvas

import dev.shibasis.reaktor.blueprint.*
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import kotlin.test.*

class BlueprintPaintMapTest {
    private val cards = listOf(Card("a", 20.0, 20.0, 80.0, 60.0, emptyList(), 0),
        Card("b", 20.0, 120.0, 80.0, 60.0, emptyList(), 0),
        Card("outside", 700.0, 20.0, 80.0, 60.0, emptyList(), 0)).associateBy { it.id }
    private val frame = Frame("group", "Component", 0.0, 0.0, 500.0, 400.0, setOf("a", "b"), emptyList(), false)
    private val wires = listOf("a", "b").map { id -> Link(id, id, "outside", null, null, LinkKind.Wire, listOf(id),
        listOf(100.0 to if (id == "a") 40.0 else 140.0, 500.0 to 40.0, 700.0 to 40.0), family = "dependency") }
    private val full = BlueprintLayout("map", cards, listOf(frame), wires, 800.0, 400.0)
    private fun levels(layout: BlueprintLayout<String> = full) = BlueprintMapLevels.prepare(layout, { 1.0 })

    @Test fun summaryCarriesExactBoundaryLooksProtectionAndAgentIdentity() {
        val one = Pin("read", "Record", true, 40.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)
        val two = one.copy(key = "write")
        val c = cards.getValue("a").copy(pins = listOf(two, one))
        val source = full.copy(cards = cards + (c.id to c))
        val level = levels(source).at(.1).frames.getValue("group")
        val boundary = one.copy(key = "boundary")
        val summary = assertNotNull(level.summary).copy(pins = listOf(boundary))
        val ref = PinRef(summary.id, boundary.key, true)
        val agent = BlueprintAgent(null, "(codex, xhigh, sol, designer)", BlueprintAgent.Activity.Live)
        val other = agent.copy(label = "(claude, max, opus, lead)", activity = BlueprintAgent.Activity.Assigned)
        val mark = BlueprintMark("Protected", Color.Red)
        val pinLook = BlueprintPinLook(Color.Green, "Reads record", Color.Blue, true)
        val looks = mapOf("a" to BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Schema", "", pins = listOf(null, pinLook), agent = agent),
            "b" to BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Task", "", agent = other))
        val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, "2 records", marks = listOf(mark))
        val look = mapSummaryLook(source, level, summary, looks, frameLook, mapOf(ref to listOf(PinRef("a", "read", true))), Color.Black)
        assertEquals(frame.label, look.title)
        assertEquals("2 nodes", look.subtitle)
        assertEquals(listOf(mark), look.marks)
        assertEquals(agent.copy(otherAgents = 1), look.agent)
        assertEquals(pinLook, look.pins.single())
        val multiple = mapSummaryLook(source, level, summary, looks, frameLook,
            mapOf(ref to listOf(PinRef("a", "read", true), PinRef("a", "write", true))), Color.Black)
        assertEquals("2 Record", multiple.pins.single()?.text)
        val supplied = mapSummaryLook(source, level, summary,
            looks + (summary.id to BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Unrelated task", "Task details",
                marks = listOf(BlueprintMark("Doing", Color.Green)), agent = agent)),
            frameLook, emptyMap(), Color.Black)
        assertEquals(frame.label, supplied.title)
        assertEquals("2 nodes", supplied.subtitle)
        assertEquals(listOf(mark, BlueprintMark("Doing", Color.Green)), supplied.marks)
        assertEquals(agent.copy(otherAgents = 1), supplied.agent)
    }

    @Test fun midpointCrossfadesSummaryMembersAndTheirCorridorsWithoutMovingAnything() {
        val levels = levels()
        val frame = levels.at(1.0).frames.getValue("group")
        val zoom = (frame.enterZoom + frame.leaveZoom) / 2
        val paint = paintMap(levels, levels.at(zoom))
        assertEquals(.5f, paint.cards.getValue("summary:group"), .00001f)
        assertEquals(.5f, paint.cards.getValue("a"), .00001f)
        assertEquals(1f, paint.cards.getValue("outside"))
        assertEquals(.5f, paint.frames.getValue("group"), .00001f)
        cards.forEach { (id, card) -> assertSame(card, paint.layout.cards[id]) }
        assertTrue(paint.layout.links.any { it.members.size == 2 })
        assertTrue(paint.layout.links.any { it.from == "a" })
        assertTrue(paint.links.all { it in 0f..1f })
    }

    @Test fun thePaintBandHasTheSameResultApproachingFromEitherDirection() {
        val levels = levels()
        val f = levels.at(1.0).frames.getValue("group")
        for (fraction in listOf(.0, .25, .5, .75, 1.0)) {
            val zoom = f.leaveZoom + (f.enterZoom - f.leaveZoom) * fraction
            val inward = paintMap(levels, levels.at(zoom))
            val outward = paintMap(levels, levels.at(zoom, setOf("group")))
            assertEquals(inward.cards, outward.cards)
            assertEquals(inward.frames, outward.frames)
            assertEquals(inward.layout.links, outward.layout.links)
            assertEquals(inward.links, outward.links)
        }
    }

    @Test fun exactLeaveHasAnOpaqueSummaryEvenIfThePreviousCutWasExpanded() {
        val levels = levels()
        val f = levels.at(1.0).frames.getValue("group")
        val paint = paintMap(levels, levels.at(f.leaveZoom, setOf("group")))
        assertEquals(setOf("summary:group", "outside"), paint.layout.cards.keys)
        assertEquals(1f, paint.cards.getValue("summary:group"))
        assertTrue(paint.layout.frames.isEmpty())
        assertTrue(paint.links.all { it > 0f })
    }

    @Test fun disablingCorridorsKeepsIndividualWireMembersAcrossThePaintBand() {
        val levels = levels()
        val frame = levels.at(1.0).frames.getValue("group")
        for (zoom in listOf(.1, (frame.enterZoom + frame.leaveZoom) / 2)) {
            val bundled = paintMap(levels, levels.at(zoom))
            val individual = paintMap(levels, levels.at(zoom, bundles = false))
            assertTrue(bundled.layout.links.any { it.members.size == 2 })
            assertTrue(individual.layout.links.all { it.members.size == 1 })
            assertEquals(wires.flatMap { it.members }.toSet(), individual.layout.links.flatMap { it.members }.toSet())
            assertEquals(bundled.layout.cards, individual.layout.cards)
            assertEquals(bundled.cards, individual.cards)
        }
        assertSame(full, paintMap(levels, levels.at(16.0, bundles = false)).layout)
    }

    @Test fun theNearStepRestoresTheExactFullNodesAndWires() {
        val levels = levels()
        val paint = paintMap(levels, levels.at(16.0))
        assertSame(full, paint.layout)
        assertSame(full.cards, paint.layout.cards)
        assertEquals(full.cards, paint.layout.cards)
        assertEquals(full.links, paint.layout.links)
        assertTrue(paint.cards.values.all { it == 1f })
        assertTrue(paint.links.all { it == 1f })
    }

    @Test fun nestedMidpointKeepsIntermediateSummariesAndTheirCorridors() {
        val outer = frame.copy(key = "outer", nodes = emptySet())
        val inner = frame.copy(parent = "outer")
        val typedCards = cards.mapValues { (id, card) -> card.copy(pins = listOf(
            Pin(if (id == "outside") "request" else "result", "Record", id != "outside", 40.0,
                dev.shibasis.reaktor.graph.core.PortWiring.Connected))) }
        val typedLinks = wires.map { it.copy(fromPort = "result", toPort = "request") }
        val levels = levels(full.copy(cards = typedCards, frames = listOf(outer, inner), links = typedLinks))
        for (bundles in listOf(true, false)) for (expanded in listOf(emptySet(), setOf("outer", "group"))) {
            val paint = paintMap(levels, levels.at(1.06, expanded, bundles = bundles))
            assertEquals(.5f, paint.cards.getValue("summary:outer"), .00001f)
            assertEquals(.25f, paint.cards.getValue("summary:group"), .00001f)
            assertEquals(.25f, paint.cards.getValue("a"), .00001f)
            assertEquals(.25f, paint.frames.getValue("group"), .00001f)
            val corridors = paint.layout.links.withIndex().filter { it.value.from == "summary:group" }
            assertTrue(corridors.isNotEmpty())
            assertTrue(corridors.all { kotlin.math.abs(paint.links[it.index] - .25f) < .00001f })
            val forwarded = paint.forwardedPorts.filterKeys { it.card == "summary:group" }
            assertEquals(setOf(PinRef("a", "result", true), PinRef("b", "result", true)),
                forwarded.values.flatten().toSet())
            typedCards.forEach { (id, card) -> assertSame(card, paint.layout.cards[id]) }
        }
    }

    @Test fun nestedOpacityIsTheProductOfThePublishedBands() {
        val outer = frame.copy(key = "outer", nodes = emptySet())
        val inner = frame.copy(parent = "outer")
        val levels = levels(full.copy(frames = listOf(outer, inner)))
        val current = levels.at(1.0)
        val paint = paintMap(levels, current)
        val parent = current.frames.getValue("outer").blend.toFloat()
        val child = current.frames.getValue("group").blend.toFloat()
        if ("a" in paint.cards) assertEquals(parent * child, paint.cards.getValue("a"), .00001f)
        if ("summary:group" in paint.cards) assertEquals(parent * (1f - child), paint.cards.getValue("summary:group"), .00001f)
        assertTrue(paint.cards.values.all { it > 0f })
    }

    @Test fun summaryBudgetKeepsSubjectBadgesAndExactBoundaryAuthority() {
        val originalPin = Pin("read", "Record", true, 40.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)
        val original = cards.getValue("a").copy(pins = listOf(originalPin))
        val source = full.copy(cards = cards + (original.id to original))
        val group = levels(source).at(.1).frames.getValue("group")
        val boundary = originalPin.copy(key = "boundary")
        val summary = assertNotNull(group.summary).copy(pins = listOf(boundary))
        assertEquals(setOf("a", "b"), group.members)
        val frameMark = BlueprintMark("Policy unknown", Color.Red)
        val memberMark = BlueprintMark("Blocked", Color.Red)
        val suppliedMark = BlueprintMark("Incomplete source", Color.Yellow, BlueprintMark.Kind.Badge)
        val pinLook = BlueprintPinLook(Color.Green, "Reads original record", Color.Blue, true)
        val agent = BlueprintAgent(null, "(codex, xhigh, sol, reviewer)", BlueprintAgent.Activity.Stale)
        var invoked = 0
        val action = BlueprintCardAction("inspect", "Inspect original group", SurfaceGraphSymbol) { invoked++ }
        val supplied = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Producer group title", "3 internal links · 1 unresolved route",
            selected = true, marks = listOf(suppliedMark), pins = listOf(pinLook), tag = "supplied-summary",
            action = "Select original group", agent = agent, actions = listOf(action))
        val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, "2 agents · 1 retained item", marks = listOf(frameMark))
        assertTrue(frameLook.count.isNotBlank() && supplied.subtitle.isNotBlank())
        val look = mapSummaryLook(source, group, summary,
            mapOf("a" to BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Original member", "", marks = listOf(memberMark)), summary.id to supplied),
            frameLook, mapOf(PinRef(summary.id, boundary.key, true) to listOf(PinRef(original.id, originalPin.key, true))), Color.Black)
        assertEquals("2 nodes", look.subtitle)
        assertEquals(frame.label, look.title)
        assertEquals(listOf(suppliedMark, frameMark), look.marks)
        assertSame(pinLook, look.pins.single())
        assertEquals(agent, look.agent)
        assertTrue(look.selected)
        assertEquals(supplied.tag, look.tag)
        assertEquals(supplied.action, look.action)
        assertSame(action, look.actions.single())
        look.actions.single().onInvoke()
        assertEquals(1, invoked)
        assertSame(original, source.cards.getValue(original.id))
        assertSame(originalPin, source.cards.getValue(original.id).pins.single())
        assertSame(full.links, source.links)
    }

    @Test fun summaryCountDoesNotPromoteSuppliedSourceDetailsIntoAnotherLine() {
        val group = levels().at(.1).frames.getValue("group")
        val summary = assertNotNull(group.summary)
        for (frameLook in listOf(null, BlueprintFrameLook(SurfaceGraphSymbol, ""), BlueprintFrameLook(SurfaceGraphSymbol, "  "))) {
            val fallback = mapSummaryLook(full, group, summary, emptyMap(), frameLook, emptyMap(), Color.Black)
            assertEquals("2 nodes", fallback.subtitle)
            val empty = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Group", " ")
            assertEquals("2 nodes", mapSummaryLook(full, group, summary, mapOf(summary.id to empty), frameLook, emptyMap(), Color.Black).subtitle)
            val supplied = empty.copy(subtitle = "Source partial · 1 unread plan")
            assertEquals("2 nodes", mapSummaryLook(full, group, summary, mapOf(summary.id to supplied), frameLook, emptyMap(), Color.Black).subtitle)
        }
    }

    @Test fun summaryCountReplacesDomainAndObservationListsWithoutChangingSource() {
        val group = levels().at(.1).frames.getValue("group")
        val summary = assertNotNull(group.summary)
        val supplied = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Group", "2 records")
        val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, "2 records")
        assertEquals("2 nodes", mapSummaryLook(full, group, summary, mapOf(summary.id to supplied), frameLook, emptyMap(), Color.Black).subtitle)
        val different = supplied.copy(subtitle = "2 records · 1 stale claim · source partial")
        assertEquals("2 nodes", mapSummaryLook(full, group, summary, mapOf(summary.id to different), frameLook, emptyMap(), Color.Black).subtitle)
        val observed = supplied.copy(subtitle = "0 observed failures · 8 retained calls · coverage unknown")
        assertEquals("2 nodes", mapSummaryLook(full, group, summary, mapOf(summary.id to observed), frameLook, emptyMap(), Color.Black).subtitle)
    }

    @Test fun summaryShowsOneCountWhileInternalLinksRemainInTheFullLayout() {
        val group = levels().at(.1).frames.getValue("group")
        val summary = assertNotNull(group.summary)
        val frameLook = BlueprintFrameLook(SurfaceGraphSymbol, "2 nodes")
        val supplied = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Group", "3 internal links")
        val look = mapSummaryLook(full, group, summary, mapOf(summary.id to supplied), frameLook, emptyMap(), Color.Black)
        assertEquals("2 nodes", look.subtitle)
        assertEquals(1, look.subtitle.lines().size)
        assertEquals(frame.label, look.title)
        assertEquals(BlueprintFace.Region, look.face)
        assertEquals(emptyList(), look.pins)
        assertSame(full.links, levels().full.links)
        val folded = summary.copy(folded = 4)
        assertEquals("4 nodes", mapSummaryLook(full, group, folded, emptyMap(), frameLook, emptyMap(), Color.Black).subtitle)
        val one = summary.copy(folded = 1)
        assertEquals("1 node", mapSummaryLook(full, group, one, emptyMap(), frameLook, emptyMap(), Color.Black).subtitle)
    }
}
