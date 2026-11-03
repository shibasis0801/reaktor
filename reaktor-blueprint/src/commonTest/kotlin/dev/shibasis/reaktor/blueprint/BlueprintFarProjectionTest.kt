package dev.shibasis.reaktor.blueprint

import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.test.*

class BlueprintFarProjectionTest {
    private val cards = listOf(card("a", 20.0), card("b", 120.0), card("c", 700.0)).associateBy { it.id }
    private val frames = listOf(
        Frame("root", "Map", 0.0, 0.0, 1000.0, 400.0, emptySet(), emptyList(), false),
        Frame("left", "Left", 0.0, 0.0, 500.0, 400.0, setOf("b"), emptyList(), false, "root"),
        Frame("leaf", "Leaf", 10.0, 10.0, 90.0, 180.0, setOf("a"), emptyList(), false, "left"),
        Frame("right", "Right", 650.0, 0.0, 350.0, 400.0, setOf("c"), emptyList(), false, "root"))
    private val wires = listOf(
        wire("forward-a", "a", "c", 50.0, listOf("raw-a", "raw-b")),
        wire("forward-b", "b", "c", 80.0, listOf("raw-c", "raw-d", "raw-e", "raw-f", "raw-g", "raw-h", "raw-i")),
        wire("reverse", "c", "a", 140.0, listOf("raw-return")).let { reverse ->
            reverse.copy(points = listOf(reverse.points.first(), 650.0 to 140.0,
                500.0 to 140.0, reverse.points.last()))
        })
    private val full = BlueprintLayout("far", cards, frames, wires, 1000.0, 400.0)
    private val levels = BlueprintMapLevels.prepare(full, { if (it.id.startsWith("summary:") || it.id == "b") .1 else 1.0 },
        summary = { if (it.key == "root") null else "summary:${it.key}" })

    @Test fun preparedBoundaryLabelsRetainOwnersBesideShortTypedAndReversedRoutesAcrossClipping() {
        val prepared = BlueprintMapLevels.prepare(full,
            { if (it.id.startsWith("summary:") || it.id == "b") .8 else 1.6 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val first = prepared.at(.5)
        var expanded = first.expanded
        for (zoom in listOf(.5, 1.0, 2.0, .5)) {
            val level = prepared.at(zoom, expanded)
            expanded = level.expanded
            for (link in level.layout.links) {
                for (camera in listOf(Viewport(40.0, 40.0, zoom), Viewport(-1000.0, 40.0, zoom))) {
                    val labels = BlueprintFarProjection.boundaryLabels(link, camera, 1512.0, 982.0, 18.0, 12.0, 3.0)
                    val narrow = BlueprintFarProjection.boundaryLabels(link, camera, 30.0, 982.0, 18.0, 12.0, 3.0)
                    assertEquals(labels.map { it.owner to it.port }, narrow.map { it.owner to it.port })
                    narrow.forEach { assertSame(link, it.link); assertNull(it.anchor) }
                    assertEquals(2, labels.size)
                    labels.forEachIndexed { index, label ->
                        val provides = index == 0
                        assertSame(link, label.link)
                        assertEquals(if (provides) link.from else link.to, label.owner)
                        assertEquals(if (provides) link.fromPort else link.toPort, label.port)
                        assertEquals(provides, label.provides)
                        assertEquals(if (provides) link.trace.first() else link.trace.last(), label.boundary)
                        assertEquals(camera.x + label.boundary.first * zoom to camera.y + label.boundary.second * zoom, label.screen)
                        assertEquals(label.screen.first !in 0.0..1512.0 || label.screen.second !in 0.0..982.0, label.clipped)
                        label.anchor?.let { (x, y) ->
                            assertTrue(x in 18.0..1494.0 && y in 12.0..970.0)
                            assertTrue(kotlin.math.hypot(x - label.screen.first, y - label.screen.second) < 50.0)
                        }
                        if (camera.x == -1000.0 && zoom == .5) {
                            assertTrue(label.clipped)
                            assertNull(label.anchor)
                        }
                    }
                    if (zoom == .5 && camera.x == 40.0) {
                        assertEquals(75.0, kotlin.math.abs(labels[1].screen.first - labels[0].screen.first))
                        labels.forEach { assertNotNull(it.anchor) }
                        val a = labels[0].anchor!!; val b = labels[1].anchor!!
                        assertTrue(kotlin.math.abs(a.first - b.first) >= 36.0 || kotlin.math.abs(a.second - b.second) >= 24.0)
                    }
                }
            }
            if (zoom == 2.0) assertSame(full, level.layout)
            if (zoom == .5) {
                assertEquals(first.layout, level.layout)
                assertEquals(first.forwardedPorts, level.forwardedPorts)
            }
        }
        assertEquals(wires, prepared.full.links)
        assertTrue(first.layout.links.any { it.from == "summary:right" })
        assertEquals(setOf(1, 9), first.layout.links.map { it.members.size }.toSet())
    }

    @Test fun shortPreparedDirectedCorridorsPlaceBothCountsOutsideInkWithoutMovingEndpoints() {
        val prepared = BlueprintMapLevels.prepare(full,
            { if (it.id.startsWith("summary:") || it.id == "b") .8 else 1.6 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val first = prepared.at(.5)
        val boxes = mutableListOf<List<Float>>()
        val halfWidth = 18f; val halfHeight = 12f
        fun overlaps(a: List<Float>, b: List<Float>) = a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]
        val obstacles = first.layout.cards.values.map { card ->
            listOf((40 + card.x * .5).toFloat(), (40 + card.y * .5).toFloat(),
                (40 + (card.x + card.width) * .5).toFloat(), (40 + (card.y + card.height) * .5).toFloat())
        } + listOf(listOf(40f, 40f, 540f, 60f))
        for (link in first.layout.links) {
            val start = link.points.first(); val end = link.points.last()
            assertEquals(75.0, kotlin.math.abs(end.first - start.first) * .5)
            val count = link.members.size
            assertTrue(BlueprintFarProjection.labelVisible(count, .5f, false, true))
            val ink = BlueprintFarProjection.corridor(count, 1.3f, .5)
            val stroke = ink.width * .5f
            val offset = assertNotNull(BlueprintFarProjection.labelOffset(
                ((end.first - start.first) * .5).toFloat(), ((end.second - start.second) * .5).toFloat(),
                halfWidth, halfHeight, stroke))
            val x = (40 + (start.first + end.first) * .25).toFloat()
            val y = (40 + (start.second + end.second) * .25).toFloat()
            val onWire = listOf(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
            if (count == 9) assertTrue(obstacles.any { overlaps(it, onWire) })
            val box = assertNotNull(listOf(1f, -1f).map { sign ->
                listOf(x + offset.first * sign - halfWidth, y + offset.second * sign - halfHeight,
                    x + offset.first * sign + halfWidth, y + offset.second * sign + halfHeight)
            }.firstOrNull { candidate -> (obstacles + boxes).none { overlaps(it, candidate) } })
            assertTrue(box[0] >= 290f && box[2] <= 365f)
            assertTrue(box[3] <= y - stroke / 2f - 3f || box[1] >= y + stroke / 2f + 3f)
            assertEquals(if (count > 1) "×$count" else "", BlueprintFarProjection.label(null, count))
            boxes += box
        }
        assertEquals(2, boxes.size)
        assertFalse(overlaps(boxes[0], boxes[1]))
        assertFalse(BlueprintFarProjection.labelVisible(1, .5f, false, false))
        assertFalse(BlueprintFarProjection.labelVisible(9, 0f, false, true))
        var previous = first.expanded
        for (zoom in listOf(1.0, 2.0, .5)) {
            val level = prepared.at(zoom, previous)
            previous = level.expanded
            if (zoom == 2.0) assertSame(full, level.layout)
            if (zoom == .5) {
                assertEquals(first.layout, level.layout)
                assertEquals(first.forwardedPorts, level.forwardedPorts)
            }
        }
        assertEquals(wires, prepared.full.links)
    }

    @Test fun nestedFoldedCorridorsKeepAsymmetricCountsDirectionsAndTypedBoundaryPorts() {
        val far = levels.at(.03)
        val forward = far.layout.links.single { it.from == "summary:left" }
        val reverse = far.layout.links.single { it.to == "summary:left" }
        assertEquals("summary:right", forward.to)
        assertEquals("summary:right", reverse.from)
        assertEquals(wires.take(2).flatMap { it.members }.sorted(), forward.members)
        assertEquals(listOf("raw-return"), reverse.members)
        assertEquals(listOf(500.0 to 50.0, 650.0 to 50.0), forward.points)
        assertEquals(listOf(650.0 to 140.0, 500.0 to 140.0), reverse.points)
        val forwardInk = BlueprintFarProjection.corridor(forward.members.size, 1.3f, far.zoom)
        val reverseInk = BlueprintFarProjection.corridor(reverse.members.size, 1.3f, far.zoom)
        assertEquals(3f, (forwardInk.width * far.zoom).toFloat(), .00001f)
        assertEquals(1f, (reverseInk.width * far.zoom).toFloat(), .00001f)
        assertEquals(6f, (forwardInk.arrow * far.zoom).toFloat(), .00001f)
        assertEquals(4f, (reverseInk.arrow * far.zoom).toFloat(), .00001f)
        assertTrue(forwardInk.width > reverseInk.width)
        far.layout.links.forEach { link ->
            for (ref in listOf(PinRef(link.from, assertNotNull(link.fromPort), true),
                PinRef(link.to, assertNotNull(link.toPort), false))) {
                val pin = far.layout.cards.getValue(ref.card).pins.single { ref.matches(ref.card, it) }
                assertEquals("Record", pin.type)
                assertTrue(ref.carries(link))
                assertTrue(far.forwardedPorts.getValue(ref).all { raw ->
                    cards.getValue(raw.card).pins.any { raw.matches(raw.card, it) && it.type == pin.type }
                })
            }
        }
        assertEquals(setOf("a", "b"), far.frames.getValue("left").members)
        assertEquals(setOf("a"), far.frames.getValue("leaf").members)
        assertEquals("summary:left", far.layout.cardOf["a"])
        assertEquals(wires.flatMap { it.members }.toSet(), far.layout.links.flatMap { it.members }.toSet())
    }

    @Test fun expandedAndReturnedLevelsKeepRawGeometryIdentityAndDirectedMembership() {
        val far = levels.at(.03)
        val middle = levels.at(.5)
        assertEquals(setOf("b", "summary:leaf", "summary:right"), middle.layout.cards.keys)
        assertEquals("summary:leaf", middle.layout.cardOf["a"])
        val near = levels.at(2.0, middle.expanded)
        assertSame(full, near.layout)
        assertTrue(near.forwardedPorts.isEmpty())
        assertEquals(wires, near.layout.links)
        cards.forEach { (id, card) -> assertSame(card, near.layout.cards[id]) }
        val returned = levels.at(.03, near.expanded)
        assertEquals(far.layout, returned.layout)
        assertEquals(far.forwardedPorts, returned.forwardedPorts)
        assertEquals(cards, levels.full.cards)
        assertEquals(frames, levels.full.frames)
        assertEquals(wires, levels.full.links)
    }

    @Test fun corridorInkAndArrowStayWithinReadableScreenCapsAtEveryZoom() {
        for (zoom in listOf(.01, .03, .1, .5, 1.0, 2.0)) {
            val one = BlueprintFarProjection.corridor(1, 1.3f, zoom)
            val many = BlueprintFarProjection.corridor(10000, 1.3f, zoom)
            assertEquals(maxOf(1f, (1.3f * zoom).toFloat()).coerceAtMost(3f), (one.width * zoom).toFloat(), .00001f)
            assertEquals(3f, (many.width * zoom).toFloat(), .00001f)
            assertEquals(4f, (one.arrow * zoom).toFloat(), .00001f)
            assertEquals(6f, (many.arrow * zoom).toFloat(), .00001f)
            for (members in listOf(1, 2, 9, 10000)) {
                val ink = BlueprintFarProjection.corridor(members, 1000f, zoom)
                assertTrue(ink.width * zoom in .99999..3.00001)
                assertTrue(ink.arrow * zoom in 3.99999..6.00001)
            }
        }
        assertEquals(3f, (BlueprintFarProjection.corridor(9, 1000f, .03).width * .03).toFloat(), .00001f)
        assertFailsWith<IllegalArgumentException> { BlueprintFarProjection.corridor(0, 1.3f, .03) }
        assertFailsWith<IllegalArgumentException> { BlueprintFarProjection.corridor(1, 1.3f, Double.NaN) }
    }

    @Test fun corridorCountIsAttachedOnceAndRetainsSuppliedMeaning() {
        assertEquals("×9", BlueprintFarProjection.label(null, 9))
        assertEquals("×9", BlueprintFarProjection.label("×9", 9))
        assertEquals("Record · ×9", BlueprintFarProjection.label("Record", 9))
        assertEquals("Record · ×9", BlueprintFarProjection.label("Record · ×9", 9))
        assertEquals("", BlueprintFarProjection.label("", 1))
        assertEquals("", BlueprintFarProjection.label("×1", 1))
        assertEquals("Record", BlueprintFarProjection.label("Record", 1))
        assertEquals("Record", BlueprintFarProjection.label("Record · ×1", 1))
    }

    @Test fun sameEndpointsKeepTypedAndReversedCorridorsSeparateThroughEveryLevel() {
        val typedCards = cards.mapValues { (_, card) -> card.copy(pins = card.pins + listOf(
            Pin("event|out", "Event", true, 45.0, PortWiring.Connected),
            Pin("event|in", "Event", false, 125.0, PortWiring.Connected))) }
        val events = wires.first().copy(id = "events", fromPort = "event|out", toPort = "event|in",
            members = (1..16).map { "event:$it" })
        val reversed = wires.first().copy(id = "reversed", reversed = true, members = listOf("raw-reversed"))
        val source = full.copy(cards = typedCards, links = wires + events + reversed)
        val prepared = BlueprintMapLevels.prepare(source,
            { if (it.id.startsWith("summary:") || it.id == "b") .1 else 1.0 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val initial = prepared.at(.03)
        val forward = initial.layout.links.filter { it.from == "summary:left" && it.to == "summary:right" }
        assertEquals(3, forward.size)
        fun type(level: BlueprintMapLevel<String>, link: Link, provides: Boolean): String {
            val id = if (provides) link.from else link.to
            val key = if (provides) link.fromPort else link.toPort
            return level.layout.cards.getValue(id).pins.single { it.key == key && it.provides == provides }.type
        }
        assertEquals(setOf(Triple("Record", false, 9), Triple("Event", false, 16), Triple("Record", true, 1)),
            forward.map { Triple(type(initial, it, true), it.reversed, it.members.size) }.toSet())
        forward.forEach { link ->
            val ink = BlueprintFarProjection.corridor(link.members.size, 1.3f, initial.zoom)
            val expected = if (link.reversed) 1f else 3f
            assertEquals(expected, (ink.width * initial.zoom).toFloat(), .00001f)
        }
        var expanded = emptySet<String>()
        for (zoom in listOf(.03, .5, 2.0, .5, .03)) {
            val level = prepared.at(zoom, expanded)
            expanded = level.expanded
            assertEquals(source.links.flatMap { it.members }.toSet(),
                level.layout.links.flatMap { it.members }.toSet() + level.layout.foldedLinks.keys)
            level.layout.links.forEach { link ->
                val originals = source.links.filter { raw -> raw.members.any(link.members::contains) }
                assertEquals(originals.flatMap { it.members }.toSet(), link.members.toSet())
                assertEquals(setOf(link.reversed), originals.map { it.reversed }.toSet())
                assertEquals(setOf(type(level, link, true)), originals.map { raw ->
                    typedCards.getValue(raw.from).pins.single { it.key == raw.fromPort && it.provides }.type
                }.toSet())
                assertEquals(type(level, link, true), type(level, link, false))
                assertEquals(setOf(link.from), originals.map { level.layout.cardOf.getValue(it.from) }.toSet())
                assertEquals(setOf(link.to), originals.map { level.layout.cardOf.getValue(it.to) }.toSet())
            }
            level.forwardedPorts.forEach { (boundary, rawPorts) ->
                val pin = level.layout.cards.getValue(boundary.card).pins.single { boundary.matches(boundary.card, it) }
                assertTrue(rawPorts.isNotEmpty())
                rawPorts.forEach { raw ->
                    val original = typedCards.getValue(raw.card).pins.single { raw.matches(raw.card, it) }
                    assertEquals(original.type, pin.type)
                    assertEquals(original.provides, pin.provides)
                }
            }
            assertSame(source, prepared.full)
            assertEquals(typedCards, source.cards)
            assertEquals(frames, source.frames)
            assertEquals(wires + events + reversed, source.links)
            if (zoom == 2.0) assertSame(source, level.layout)
        }
        val returned = prepared.at(.03, expanded)
        assertEquals(initial.layout, returned.layout)
        assertEquals(initial.forwardedPorts, returned.forwardedPorts)
    }

    @Test fun preparedNestedRoundTripRetainsEveryExactRawBoundaryEndpointWhilePaintingDirectedCounts() {
        val prepared = BlueprintMapLevels.prepare(full,
            { if (it.id.startsWith("summary:") || it.id == "b") .1 else 1.0 },
            summary = { if (it.key == "root") null else "summary:${it.key}" })
        val first = prepared.at(.03)
        var expanded = emptySet<String>()
        for (zoom in listOf(.03, .5, 2.0, .03)) {
            val level = prepared.at(zoom, expanded)
            expanded = level.expanded
            val expectedCounts = when (zoom) {
                .03 -> mapOf(("summary:left" to "summary:right") to 9, ("summary:right" to "summary:left") to 1)
                .5 -> mapOf(("summary:leaf" to "summary:right") to 2, ("b" to "summary:right") to 7,
                    ("summary:right" to "summary:leaf") to 1)
                else -> mapOf(("a" to "c") to 2, ("b" to "c") to 7, ("c" to "a") to 1)
            }
            assertEquals(expectedCounts, level.layout.links.associate { (it.from to it.to) to it.members.size })
            assertEquals(wires.flatMap { it.members }.toSet(), level.layout.links.flatMap { it.members }.toSet())
            val ink = mutableMapOf<Int, BlueprintFarProjection>()
            level.layout.links.forEach { link ->
                val originals = wires.filter { raw -> raw.members.any(link.members::contains) }
                assertEquals(originals.flatMap { it.members }.toSet(), link.members.toSet())
                assertEquals(setOf(link.reversed), originals.map { it.reversed }.toSet())
                for (provides in listOf(true, false)) {
                    val displayed = PinRef(if (provides) link.from else link.to,
                        assertNotNull(if (provides) link.fromPort else link.toPort), provides)
                    val expected = originals.map { raw -> PinRef(if (provides) raw.from else raw.to,
                        assertNotNull(if (provides) raw.fromPort else raw.toPort), provides) }.toSet()
                    val actual = level.forwardedPorts[displayed]?.toSet() ?: setOf(displayed)
                    assertEquals(expected, actual, "Every exact original endpoint must survive at each prepared level")
                    val pin = level.layout.cards.getValue(displayed.card).pins.single { displayed.matches(displayed.card, it) }
                    assertTrue(displayed.carries(link))
                    assertEquals("Record", pin.type)
                    expected.forEach { raw ->
                        val original = cards.getValue(raw.card).pins.single { raw.matches(raw.card, it) }
                        assertEquals(original.type, pin.type)
                        assertEquals(original.provides, pin.provides)
                    }
                }
                if (link.from !in cards || link.to !in cards) {
                    ink[link.members.size] = BlueprintFarProjection.corridor(link.members.size, 1.3f, level.zoom)
                    assertEquals(if (link.members.size > 1) "×${link.members.size}" else "", BlueprintFarProjection.label(null, link.members.size))
                }
            }
            if (zoom == 2.0) {
                assertTrue(ink.isEmpty())
                assertTrue(level.forwardedPorts.isEmpty())
                assertSame(full, level.layout)
                wires.forEach { original -> assertSame(original, level.layout.links.single { it.id == original.id }) }
            } else {
                assertEquals(expectedCounts.values.toSet(), ink.keys)
                assertTrue(ink.getValue(expectedCounts.values.max()).width > ink.getValue(1).width,
                    "The larger actual directed corridor must retain stronger ink than its single-member return")
            }
            assertSame(full, prepared.full)
            cards.forEach { (id, card) -> assertSame(card, prepared.full.cards.getValue(id)) }
            assertEquals(frames, prepared.full.frames)
            assertEquals(wires, prepared.full.links)
        }
        val returned = prepared.at(.03, expanded)
        assertEquals(first.layout, returned.layout)
        assertEquals(first.forwardedPorts, returned.forwardedPorts)
    }

    private fun card(id: String, x: Double) = Card(id, x, 20.0, 40.0, 140.0,
        listOf(Pin("raw|out", "Record", true, 30.0, PortWiring.Connected),
            Pin("raw|in", "Record", false, 120.0, PortWiring.Connected)), 0)

    private fun wire(id: String, from: String, to: String, y: Double, members: List<String>): Link {
        val left = cards.getValue(from)
        val right = cards.getValue(to)
        return Link(id, from, to, "raw|out", "raw|in", LinkKind.Wire, members,
            listOf(left.x + left.width / 2 to y, right.x + right.width / 2 to y), family = "record")
    }
}
