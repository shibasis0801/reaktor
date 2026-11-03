package dev.shibasis.reaktor.blueprint

import kotlin.test.*

class BlueprintMapLevelsTest {
    private val cards = listOf(card("a", 20.0, 20.0), card("b", 20.0, 80.0), card("c", 220.0, 20.0), card("d", 220.0, 80.0)).associateBy { it.id }
    private val frames = listOf(
        Frame("root", "Map", 0.0, 0.0, 300.0, 120.0, emptySet(), emptyList(), false),
        Frame("left", "Left", 0.0, 0.0, 100.0, 120.0, setOf("a", "b"), emptyList(), false, "root"),
        Frame("right", "Right", 200.0, 0.0, 100.0, 120.0, setOf("c", "d"), emptyList(), false, "root"))
    private val links = listOf(
        link("ab", "a", "b", listOf(60.0 to 30.0, 70.0 to 30.0, 70.0 to 90.0, 20.0 to 90.0)),
        link("cd", "c", "d", listOf(260.0 to 30.0, 270.0 to 30.0, 270.0 to 90.0, 220.0 to 90.0)),
        link("ac", "a", "c", listOf(60.0 to 30.0, 100.0 to 30.0, 200.0 to 30.0, 220.0 to 30.0)),
        link("bd", "b", "d", listOf(60.0 to 90.0, 100.0 to 90.0, 200.0 to 90.0, 220.0 to 90.0)))
    private val full = BlueprintLayout("map", cards, frames, links, 300.0, 120.0)
    private fun prepare(scene: BlueprintLayout<String> = full) = BlueprintMapLevels.prepare(scene,
        titleZoom = { if (it.id.startsWith("summary:")) .1 else 1.0 }, summary = { if (it.key == "root") null else "summary:${it.key}" })

    @Test fun zoomNeverChangesAnyFullLayoutPositionOrRoute() {
        val levels = prepare()
        val positions = full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height) }
        val points = full.links.associate { it.id to it.points }
        var previous = emptySet<String>()
        for (zoom in listOf(.01, .1, .5, 1.05, 1.2, 2.0, 1.05, .99, .1)) {
            val level = levels.at(zoom, previous)
            previous = level.expanded
            assertEquals(positions, levels.full.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height) })
            assertEquals(points, levels.full.links.associate { it.id to it.points })
            level.layout.cards.filterKeys { it in cards }.forEach { (id, card) -> assertSame(full.cards[id], card) }
        }
    }

    @Test fun summariesFillTheirFramesAndPartitionEverySourceWire() {
        val level = prepare().at(.5)
        assertEquals(setOf("summary:left", "summary:right"), level.layout.cards.keys)
        assertEquals(listOf(0.0, 0.0, 100.0, 120.0), level.layout.cards.getValue("summary:left").let { listOf(it.x, it.y, it.width, it.height) })
        assertEquals(2, level.layout.cards.getValue("summary:left").folded)
        assertEquals(setOf("a", "b"), level.frames.getValue("left").members)
        assertEquals(mapOf("ab" to "summary:left", "cd" to "summary:right"), level.layout.foldedLinks)
        val corridor = level.layout.links.single()
        assertEquals(setOf("ac", "bd"), corridor.members.toSet())
        assertEquals("summary:left", corridor.from)
        assertEquals("summary:right", corridor.to)
        assertEquals(listOf(100.0 to 30.0, 200.0 to 30.0), corridor.points)
        assertEquals(links.map { it.id }.toSet(), corridor.members.toSet() + level.layout.foldedLinks.keys)
        assertTrue(corridor.members.none { it in level.layout.foldedLinks })
    }

    @Test fun expandedLevelsRestoreIndividualWiresWithoutChangingTheirDoors() {
        val level = prepare().at(2.0)
        assertEquals(cards, level.layout.cards)
        assertEquals(links, level.layout.links)
        assertTrue(level.layout.foldedLinks.isEmpty())
        assertEquals(full.cardOf, level.layout.cardOf)
        assertSame(links[2], level.layout.links.first { it.id == "ac" })
    }

    @Test fun enterAndLeaveUseOnePublishedHysteresisBand() {
        val levels = prepare()
        val low = levels.at(1.05)
        assertFalse(low.frames.getValue("left").expanded)
        assertEquals(1.0, low.frames.getValue("left").leaveZoom)
        assertEquals(1.12, low.frames.getValue("left").enterZoom, 1e-9)
        assertTrue(low.frames.getValue("left").blend in 0.0..1.0)
        val high = levels.at(1.2)
        assertTrue(high.frames.getValue("left").expanded)
        assertTrue(levels.at(1.05, high.expanded).frames.getValue("left").expanded)
        assertFalse(levels.at(.99, high.expanded).frames.getValue("left").expanded)
        assertEquals(low.frames.getValue("left").blend, levels.at(1.05, high.expanded).frames.getValue("left").blend)
    }

    @Test fun revealAlwaysResolvesTheFullTargetBounds() {
        val levels = prepare()
        levels.at(.01)
        assertSame(cards["a"], levels.targetBounds("a"))
        assertSame(frames[1], levels.targetBounds("left"))
        assertSame(frames[1], levels.targetBounds("summary:left"))
        assertNull(levels.targetBounds("missing"))
    }

    @Test fun invalidOwnershipAndZoomAreRefusedWithoutMutatingTheSource() {
        assertFailsWith<IllegalArgumentException> { prepare(full.copy(frames = frames + frames[1].copy(key = "duplicate"))) }
        assertFailsWith<IllegalArgumentException> { prepare(full.copy(frames = listOf(frames[1].copy(parent = "right"), frames[2].copy(parent = "left")))) }
        assertFailsWith<IllegalArgumentException> { prepare().at(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { prepare().at(0.0) }
        assertEquals(4, full.cards.size)
    }

    @Test fun summaryBoundaryPinsForwardExactSourcePortsWithoutChangingTheirIdentity() {
        val pins = cards.mapValues { (_, card) -> card.copy(pins = listOf(
            Pin("exact.out|key", "Record", true, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected),
            Pin("exact.in|key", "Record", false, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected))) }
        val wired = full.copy(cards = pins, links = links.map { it.copy(fromPort = "exact.out|key", toPort = "exact.in|key") })
        val levels = prepare(wired)
        val level = levels.at(.5)
        val corridor = level.layout.links.single()
        val from = PinRef(corridor.from, corridor.fromPort!!, true)
        val to = PinRef(corridor.to, corridor.toPort!!, false)
        assertEquals(listOf(PinRef("a", "exact.out|key", true), PinRef("b", "exact.out|key", true)), level.forwardedPorts[from])
        assertEquals(listOf(PinRef("c", "exact.in|key", false), PinRef("d", "exact.in|key", false)), level.forwardedPorts[to])
        for (ref in listOf(from, to)) {
            val pin = level.layout.cards.getValue(ref.card).pins.single { ref.matches(ref.card, it) }
            assertEquals("Record", pin.type)
            assertTrue(ref.carries(corridor))
        }
        assertEquals(setOf("ac", "bd"), corridor.members.toSet())
        val near = levels.at(2.0)
        assertSame(wired, near.layout)
        assertTrue(near.forwardedPorts.isEmpty())
        assertEquals(wired.cards, near.layout.cards)
        assertEquals(wired.links, near.layout.links)
    }

    @Test fun bundleChoicePreservesParallelSelfBoundaryAndOriginalMemberIdentities() {
        val pins = (cards + ("e" to card("e", 420.0, 20.0))).mapValues { (_, card) -> card.copy(pins = listOf(
            Pin("exact.out|key", "Record", true, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected),
            Pin("exact.in|key", "Record", false, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected))) }
        val source = (links + links[2].copy(id = "ac:parallel", members = listOf("parallel:second", "parallel:first")) +
            link("aa", "a", "a", listOf(60.0 to 30.0, 80.0 to 30.0, 80.0 to 10.0, 20.0 to 10.0, 20.0 to 30.0)) +
            link("ee", "e", "e", listOf(460.0 to 30.0, 480.0 to 30.0, 480.0 to 10.0, 420.0 to 10.0, 420.0 to 30.0)))
            .map { it.copy(fromPort = "exact.out|key", toPort = "exact.in|key") }
        val wired = full.copy(cards = pins, links = source, width = 500.0)
        val levels = prepare(wired)
        val sourceIds = source.flatMap { it.members }.toSet()
        val positions = wired.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) }
        val routes = source.associate { it.id to it.points }
        for (bundles in listOf(true, false)) {
            val level = levels.at(.5, bundles = bundles)
            assertEquals(bundles, level.bundles)
            val visible = level.layout.links.flatMap { it.members }.toSet()
            assertEquals(sourceIds, visible + level.layout.foldedLinks.keys)
            assertTrue(visible.intersect(level.layout.foldedLinks.keys).isEmpty())
            assertEquals("summary:left", level.layout.foldedLinks["aa"])
            assertSame(source.last(), level.layout.links.single { it.id == "ee" })
            val crossing = level.layout.links.filter { it.from == "summary:left" && it.to == "summary:right" }
            assertEquals(if (bundles) 1 else 3, crossing.size)
            if (!bundles) crossing.forEach { projected ->
                assertEquals(source.single { it.id == projected.id }.members, projected.members)
            }
            assertEquals(setOf(PinRef("a", "exact.out|key", true), PinRef("b", "exact.out|key", true),
                PinRef("c", "exact.in|key", false), PinRef("d", "exact.in|key", false)), level.forwardedPorts.values.flatten().toSet())
            level.forwardedPorts.forEach { (boundary, originals) ->
                val pin = level.layout.cards.getValue(boundary.card).pins.single { boundary.matches(boundary.card, it) }
                originals.forEach { original ->
                    val sourcePin = wired.cards.getValue(original.card).pins.single { original.matches(original.card, it) }
                    assertEquals(sourcePin.type, pin.type)
                    assertEquals(original.provides, pin.provides)
                }
                assertTrue(crossing.any { boundary.carries(it) })
                if (!bundles) assertEquals(1, originals.size)
            }
            assertSame(wired, levels.full)
            assertEquals(positions, wired.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) })
            assertEquals(routes, wired.links.associate { it.id to it.points })
            val near = levels.at(2.0, bundles = bundles)
            assertSame(wired, near.layout)
            assertEquals(bundles, near.bundles)
            assertTrue(near.forwardedPorts.isEmpty())
            assertTrue(near.layout.foldedLinks.isEmpty())
        }
        println("BlueprintMapLevels bundle identity verified modes=2")
    }

    @Test fun mixedTypedSummaryBoundariesKeepEveryOriginalPortAtEveryDepth() {
        val typed = cards.mapValues { (id, card) ->
            val type = if (id in setOf("a", "c")) "Record" else "Unknown"
            card.copy(pins = listOf(Pin("same|raw.key", type, true, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected), Pin("same|raw.key", type, false, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)))
        }
        val source = links.map { it.copy(fromPort = "same|raw.key", toPort = "same|raw.key") }
        val nested = frames + Frame("leaf", "Leaf", 10.0, 10.0, 70.0, 40.0, setOf("a"), emptyList(), false, "left")
        val wired = full.copy(cards = typed, frames = nested.map { if (it.key == "left") it.copy(nodes = setOf("b")) else it }, links = source)
        val levels = prepare(wired)
        val sourceIds = source.flatMap { it.members }.toSet()
        for (bundles in listOf(true, false)) for (zoom in listOf(.05, .5, 1.05, 1.2, 2.0, .5)) {
            val level = levels.at(zoom, bundles = bundles)
            val visible = level.layout.links.flatMap { it.members }.toSet()
            assertEquals(sourceIds, visible + level.layout.foldedLinks.keys)
            assertTrue(visible.intersect(level.layout.foldedLinks.keys).isEmpty())
            level.layout.links.forEach { link ->
                for ((cardId, key, provides) in listOf(Triple(link.from, link.fromPort, true), Triple(link.to, link.toPort, false))) {
                    val ref = PinRef(cardId, assertNotNull(key), provides)
                    val pin = level.layout.cards.getValue(cardId).pins.single { ref.matches(cardId, it) }
                    val refs = if (cardId in wired.cards) listOf(ref) else assertNotNull(level.forwardedPorts[ref])
                    refs.forEach { original ->
                        val actual = wired.cards.getValue(original.card).pins.single { original.matches(original.card, it) }
                        assertEquals(actual.type, pin.type)
                        assertEquals(actual.provides, provides)
                        assertEquals("same|raw.key", original.key)
                    }
                }
            }
            assertSame(wired, levels.full)
            if (zoom >= 1.2) assertSame(wired, level.layout)
        }
    }

    @Test fun bundleChoiceNeverChangesThePreparedThresholdsOrHysteresis() {
        val levels = prepare()
        var previous = emptySet<String>()
        for (zoom in listOf(.01, .5, 1.05, 1.2, 1.05, .99, .01)) {
            val bundled = levels.at(zoom, previous)
            val separate = levels.at(zoom, previous, bundles = false)
            assertTrue(bundled.bundles)
            assertFalse(separate.bundles)
            assertEquals(bundled.expanded, separate.expanded)
            assertEquals(bundled.frames, separate.frames)
            assertEquals(bundled.layout.cards, separate.layout.cards)
            assertEquals(bundled.layout.foldedLinks, separate.layout.foldedLinks)
            previous = bundled.expanded
        }
        println("BlueprintMapLevels bundle thresholds verified modes=2")
    }

    @Test fun threeNestedTypedBoundariesForwardEveryOriginalCrossing() {
        val sourceCards = listOf(card("inside", 40.0, 40.0), card("outside", 600.0, 40.0)).associate { card ->
            card.id to card.copy(pins = listOf(Pin("same|key", "DeclaredRecord", true, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected),
                Pin("same|key", "ExternalUnknown", false, 10.0, dev.shibasis.reaktor.graph.core.PortWiring.Connected)))
        }
        val sourceLinks = listOf(
            link("crossing", "inside", "outside", listOf(80.0 to 50.0, 600.0 to 50.0)),
            link("parallel", "inside", "outside", listOf(80.0 to 50.0, 600.0 to 50.0)),
            link("internal", "inside", "inside", listOf(80.0 to 50.0, 90.0 to 50.0, 90.0 to 30.0, 40.0 to 30.0, 40.0 to 50.0)))
            .map { it.copy(fromPort = "same|key", toPort = "same|key") }
        val sourceFrames = listOf(
            Frame("outer", "Outer", 0.0, 0.0, 500.0, 120.0, emptySet(), emptyList(), false),
            Frame("middle", "Middle", 10.0, 10.0, 300.0, 100.0, emptySet(), emptyList(), false, "outer"),
            Frame("inner", "Inner", 20.0, 20.0, 150.0, 80.0, setOf("inside"), emptyList(), false, "middle"))
        val source = BlueprintLayout("three-depth", sourceCards, sourceFrames, sourceLinks, 640.0, 120.0)
        val levels = BlueprintMapLevels.prepare(source, titleZoom = { when (it.id) {
            "summary:middle" -> .2
            "summary:inner" -> .5
            else -> 1.0
        } })
        val originalMembers = sourceLinks.flatMap { it.members }.toSet()
        val geometry = sourceCards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) }
        for (bundles in listOf(true, false)) {
            for ((zoom, owner) in listOf(.1 to "summary:outer", .3 to "summary:middle", .7 to "summary:inner")) {
                val cut = levels.at(zoom, bundles = bundles)
                assertEquals(setOf(owner, "outside"), cut.layout.cards.keys)
                val visible = cut.layout.links.flatMap { it.members }.toSet()
                assertEquals(originalMembers, visible + cut.layout.foldedLinks.keys)
                assertTrue(visible.intersect(cut.layout.foldedLinks.keys).isEmpty())
                assertEquals(mapOf("internal" to owner), cut.layout.foldedLinks)
                assertEquals(if (bundles) 1 else 2, cut.layout.links.size)
                cut.layout.links.forEach { wire ->
                    assertEquals(owner, wire.from)
                    assertEquals("outside", wire.to)
                    assertEquals("same|key", wire.toPort)
                    val boundary = PinRef(owner, assertNotNull(wire.fromPort), true)
                    assertEquals(listOf(PinRef("inside", "same|key", true)), cut.forwardedPorts[boundary])
                    val pin = cut.layout.cards.getValue(owner).pins.single { boundary.matches(owner, it) }
                    assertEquals("DeclaredRecord", pin.type)
                    assertEquals("ExternalUnknown", cut.layout.cards.getValue("outside").pins.single { !it.provides }.type)
                }
                assertSame(source, levels.full)
                assertEquals(geometry, source.cards.mapValues { (_, card) -> listOf(card.x, card.y, card.width, card.height, card.pins) })
                assertEquals(sourceLinks, source.links)
            }
            assertSame(source, levels.at(2.0, bundles = bundles).layout)
        }
    }

    @Test fun measuredOneRowCodeEnvelopeKeepsMidSummariesUntilReadableAndReturnsExactly() {
        val wiring = dev.shibasis.reaktor.graph.core.PortWiring.Connected
        val spec = PinSpec("value", "kotlin.String", wiring)
        val height = BlueprintEngine.cardHeight(BlueprintNode("measured", 0, listOf(spec), listOf(spec)))
        assertEquals(72.0, height)
        val originals = (0 until 14).associate { index ->
            val id = "member:$index"
            val x = if (index < 12) 65.0 + index * 800.0 else 10200.0 + (index - 12) * 400.0
            id to Card(id, x, 110.0, BlueprintEngine.CardWidth, height,
                listOf(Pin(spec.key, spec.type, false, 55.0, wiring), Pin(spec.key, spec.type, true, 55.0, wiring)), 0)
        }
        val scopes = listOf(
            Frame("envelope-root", "Envelope", 0.0, 0.0, 11400.0, 400.0, emptySet(), emptyList(), false),
            Frame("envelope-main", "Main", 47.0, 70.0, 9838.0, 238.0,
                originals.keys.take(12).toSet(), emptyList(), false, "envelope-root"),
            Frame("envelope-peers", "Peers", 10182.0, 70.0, 1000.0, 238.0,
                originals.keys.drop(12).toSet(), emptyList(), false, "envelope-root"))
        fun edge(index: Int, from: String, to: String) = Link("wire:$index", from, to, spec.key, spec.key,
            LinkKind.Wire, listOf("wire:$index"), listOf(originals.getValue(from).let { it.x + it.width to it.y + 55.0 },
                originals.getValue(to).let { it.x to it.y + 55.0 }), family = spec.type)
        val wires = (0 until 11).map { edge(it, "member:$it", "member:${it + 1}") } +
            listOf(edge(11, "member:0", "member:12"), edge(12, "member:1", "member:13"), edge(13, "member:13", "member:1"))
        val source = BlueprintLayout("measured-envelope", originals, scopes, wires, 11400.0, 400.0)
        val levels = source.blueprintMapLevels()
        val middle = levels.at(.5)
        val target = middle.frames.getValue("envelope-main")
        assertEquals(originals.keys.take(12).toSet(), target.members)
        assertEquals(2.0 / 3.0, target.leaveZoom, 1e-9)
        assertEquals((2.0 / 3.0) * 1.12, target.enterZoom, 1e-9)
        assertFalse(target.expanded)
        assertEquals(2, middle.layout.cards.size)
        assertTrue(middle.layout.frames.none { it.key == target.frame.key })
        assertEquals(wires.map { it.id }.toSet(), middle.layout.links.flatMap { it.members }.toSet() + middle.layout.foldedLinks.keys)
        assertEquals(3, middle.layout.links.flatMap { it.members }.size)
        assertEquals(11, middle.layout.foldedLinks.size)
        for (density in listOf(1.0, 2.0)) {
            val physicalHeight = height * .5 * density
            assertEquals(36.0 * density, physicalHeight)
            assertTrue(physicalHeight / density < 48.0)
            assertTrue(originals.values.all { it.width * .5 >= 128.0 })
            val expanded = levels.at(target.enterZoom)
            assertTrue(expanded.frames.getValue(target.frame.key).expanded)
            assertSame(source, expanded.layout)
            assertEquals(originals, expanded.layout.cards)
            assertEquals(scopes, expanded.layout.frames)
            assertEquals(wires, expanded.layout.links)
            val band = (target.enterZoom + target.leaveZoom) / 2.0
            assertFalse(levels.at(band).frames.getValue(target.frame.key).expanded)
            assertTrue(levels.at(band, expanded.expanded).frames.getValue(target.frame.key).expanded)
            val returned = levels.at(.5, expanded.expanded)
            assertFalse(returned.frames.getValue(target.frame.key).expanded)
            assertEquals(middle.layout, returned.layout)
            assertEquals(originals, levels.full.cards)
            assertEquals(scopes, levels.full.frames)
            assertEquals(wires, levels.full.links)
            assertEquals(originals.keys, returned.layout.cardOf.keys)
            assertTrue(returned.forwardedPorts.values.flatten().all { ref ->
                originals.getValue(ref.card).pins.any { it.key == ref.key && it.provides == ref.provides && it.type == spec.type }
            })
            assertEquals(originals, levels.at(2.0).layout.cards)
        }
    }

    @Test fun denseNestedMembersUnfoldTogetherWithoutObscuringRawCardsOrTypedContext() {
        val wiring = dev.shibasis.reaktor.graph.core.PortWiring.Connected
        val originals = (0 until 20).associate { index ->
            val id = "partial:$index"
            val tall = index < 4 || index in 12..13
            val nested = index >= 12
            id to Card(id, 80.0 + (if (nested) index - 12 else index) * 500.0, if (nested) 1200.0 else 140.0,
                320.0, if (tall) 240.0 else 72.0,
                listOf(Pin("raw:value", "example.Record", true, 55.0, wiring), Pin("raw:value", "example.Record", false, 55.0, wiring)), 0)
        }
        val frames = listOf(
            Frame("partial-root", "Root", 0.0, 0.0, 6500.0, 1900.0, emptySet(), emptyList(), false),
            Frame("partial-dense", "Dense", 30.0, 70.0, 6200.0, 1700.0, originals.keys.take(12).toSet(), emptyList(), false, "partial-root"),
            Frame("partial-nested", "Nested", 60.0, 1100.0, 4100.0, 550.0, originals.keys.drop(12).toSet(), emptyList(), false, "partial-dense"))
        fun edge(id: String, from: String, to: String) = Link(id, from, to, "raw:value", "raw:value", LinkKind.Wire,
            listOf(id), listOf(originals.getValue(from).let { it.x + it.width to it.y + 55.0 },
                originals.getValue(to).let { it.x to it.y + 55.0 }), family = "example.Record")
        val links = originals.keys.toList().let { ids -> ids.indices.map { edge("partial-wire:$it", ids[it], ids[(it + 1) % ids.size]) } } +
            listOf(edge("partial-parallel", "partial:3", "partial:4"), edge("partial-reverse", "partial:4", "partial:3"),
                edge("partial-self", "partial:0", "partial:0"))
        val source = BlueprintLayout("partial", originals, frames, links, 6500.0, 1900.0, cardOf = originals.keys.associateWith { it })
        val levels = source.blueprintMapLevels()
        val far = levels.at(.1)
        val belowFloor = levels.at(.399)
        assertTrue(belowFloor.memberBlends.isEmpty())
        assertTrue(belowFloor.layout.cards.keys.intersect(originals.keys).isEmpty())
        val middle = levels.at(.5, far.expanded)
        assertEquals(setOf("summary:partial-dense"), middle.layout.cards.keys)
        assertTrue(middle.memberBlends.isEmpty())
        for (key in listOf("partial-dense", "partial-nested")) {
            val frame = middle.frames.getValue(key)
            assertFalse(frame.expanded)
            assertEquals(48.0 / 72.0, frame.leaveZoom, 1e-9)
            assertEquals(48.0 / 72.0 * 1.12, frame.enterZoom, 1e-9)
            assertTrue(frame.members.none { it in middle.layout.cards },
                "A visible summary must exclusively own its raw members")
        }
        originals.keys.forEach { id ->
            assertEquals("summary:partial-dense", middle.layout.cardOf[id])
            assertSame(originals[id], levels.targetBounds(id))
        }
        for (bundles in listOf(true, false)) {
            val cut = levels.at(.9, bundles = bundles)
            assertSame(source, cut.layout)
            assertEquals(links.map { it.id }.toSet(), cut.layout.links.flatMap { it.members }.toSet() + cut.layout.foldedLinks.keys)
            cut.layout.links.forEach { shown -> shown.members.forEach { id ->
                val original = links.single { it.id == id }
                assertEquals(original.from, shown.from)
                assertEquals(original.fromPort, shown.fromPort)
                assertEquals(original.to, shown.to)
                assertEquals(original.toPort, shown.toPort)
            } }
        }
        val near = levels.at(.9, middle.expanded)
        assertSame(source, near.layout)
        val band = (48.0 / 72.0) * 1.06
        assertFalse(levels.at(band).frames.getValue("partial-dense").expanded)
        assertTrue(levels.at(band, near.expanded).frames.getValue("partial-dense").expanded)
        val returned = levels.at(.1, near.expanded)
        assertEquals(far.layout, returned.layout)
        assertEquals(far.memberBlends, returned.memberBlends)
        assertEquals(originals, levels.full.cards)
        assertEquals(frames, levels.full.frames)
        assertEquals(links, levels.full.links)
        val custom = BlueprintMapLevels.prepare(source.copy(cards = originals.mapValues { (_, card) ->
            card.copy(width = 80.0, height = 60.0)
        }), { 1.0 })
        val publishedMidpoint = custom.at(1.06)
        assertTrue(publishedMidpoint.memberBlends.isEmpty())
        publishedMidpoint.frames.values.forEach { frame -> assertEquals(.5, frame.blend, 1e-9) }
        assertTrue(publishedMidpoint.frames.values.none { it.expanded })
    }

    private fun card(id: String, x: Double, y: Double) = Card(id, x, y, 40.0, 20.0, emptyList(), 0)
    private fun link(id: String, from: String, to: String, points: List<Pair<Double, Double>>) = Link(id, from, to, null, null, LinkKind.Wire, listOf(id), points, family = "dependency")
}
