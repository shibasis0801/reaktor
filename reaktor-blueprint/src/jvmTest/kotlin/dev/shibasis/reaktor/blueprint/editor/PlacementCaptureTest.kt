package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.blueprint.capture.Capture
import dev.shibasis.reaktor.blueprint.capture.CaptureAnchor
import dev.shibasis.reaktor.blueprint.capture.CaptureAsset
import dev.shibasis.reaktor.blueprint.capture.CaptureAuthor
import dev.shibasis.reaktor.blueprint.capture.toCapture
import dev.shibasis.reaktor.graph.core.GraphShape
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlacementCaptureTest {
    private val json = Json { encodeDefaults = false }
    private val at = Spot(12.5, -8.0)
    private val world = CaptureAnchor.World(at)
    private val author = CaptureAuthor("(codex, xhigh, engine)", "bpo-engine", 4_294_967_296L)
    private val image = CaptureAsset("image", "document/assets/image", "image/png")

    @Test
    fun oldPlacementsKeepTheirFieldsAndExposeLegacyCaptures() {
        val old = """{"cards":{"node":{"x":12.5,"y":-8.0}},"notes":[{"id":"frame","text":"Context","x":1.0,"y":2.0,"width":200.0,"height":80.0,"tint":17,"parent":"parent"}],"remarks":{"node":"Question"},"marks":{"1":{"x":0.0,"y":0.0,"width":800.0,"height":600.0}},"bends":{"wire":[{"x":3.0,"y":4.0}]}}"""
        val placement = json.decodeFromString(Placement.serializer(), old)
        assertTrue(placement.capture.isEmpty())
        assertTrue(placement.assets.isEmpty())
        assertTrue(placement.folded.isEmpty())
        assertEquals(placement.notes.single().toCapture(), placement.captures[0])
        val remark = assertIs<Capture.Comment>(placement.captures[1])
        assertEquals("remark:node", remark.id)
        assertEquals("Question", remark.text)
        assertEquals(CaptureAnchor.Node("node", Spot(0.0, 0.0), at, "node"), remark.anchor)
        val encoded = json.encodeToString(Placement.serializer(), placement)
        assertFalse("\"capture\"" in encoded)
        assertFalse("\"captures\"" in encoded)
        assertFalse("\"folded\"" in encoded)
        assertEquals(placement, json.decodeFromString(Placement.serializer(), encoded))
    }

    @Test
    fun oldChangeSetsDecodeAndReopenWithoutAFormatChange() {
        val old = """{"format":"reaktor.changes/1","id":"draft","title":"Saved graph","revision":7,"savedAt":"2026-10-30T00:00:00Z","graph":{"changes":[],"layout":{"cards":{"node":{"x":9.0,"y":10.0}},"notes":[{"id":"note-old","text":"Retained","x":1.0,"y":2.0,"width":100.0,"height":50.0}],"remarks":{"node":"Old remark"}}}}"""
        val set = ChangeSet.decode(old)
        val layout = set.graph!!.layout!!
        assertEquals(ChangeSet.Format, set.format)
        assertEquals(Spot(9.0, 10.0), Placement().with(layout).cards["node"])
        assertEquals(layout, ChangeSet.decode(set.encode()).graph!!.layout)
        assertEquals(2, layout.captures.size)
    }

    @Test
    fun everyCaptureKindAndAnchorSurvivesSerialization() {
        val placement = Placement(capture = allCaptures(), assets = mapOf(image.id to image), folded = setOf("scope", "nested"))
        val decoded = json.decodeFromString(Placement.serializer(), json.encodeToString(Placement.serializer(), placement))
        assertEquals(placement, decoded)
        assertEquals(11, decoded.capture.size)
        assertEquals(6, decoded.capture.map { it.anchor::class }.toSet().size)
        assertEquals(author, decoded.capture.first().author)
        assertEquals(image, decoded.assets["image"])
    }

    @Test
    fun deltasPreserveEditsRemovalsOrderAndUnchangedPresentation() {
        val origin = origin()
        val next = origin.copy(
            cards = origin.cards + ("node" to Spot(80.0, 90.0)),
            notes = listOf(origin.notes.single().copy(text = "Edited frame"), Note("note-new", "New frame", 8.0, 9.0, 60.0, 70.0)),
            remarks = mapOf("other" to "New question"),
            capture = listOf(Capture.Text("new", "New text", world), origin.capture[0].let { assertIs<Capture.Text>(it).copy(text = "Edited text") }),
            assets = mapOf("image" to image.copy(reference = "document/assets/replacement")),
            folded = setOf("nested"),
        )
        val delta = next.since(origin)
        assertEquals(setOf("scope"), delta.unfolded)
        assertEquals(setOf("spare"), delta.removedAssets)
        assertEquals(setOf("removed", "remark:node"), delta.removedCaptures)
        val reopened = origin.with(json.decodeFromString(Placement.serializer(), json.encodeToString(Placement.serializer(), delta)))
        assertEquals(next, reopened)
        assertEquals(next, reopened.with(delta))
        assertEquals(origin.marks, reopened.marks)
        assertEquals(origin.bends, reopened.bends)
    }

    @Test
    fun removingTheLastCaptureAssetAndFoldIsNotAnEmptyPatch() {
        val origin = origin()
        val cleared = origin.copy(notes = emptyList(), remarks = emptyMap(), capture = emptyList(), assets = emptyMap(), folded = emptySet())
        val delta = cleared.since(origin)
        assertTrue(delta.capture.isEmpty())
        assertTrue(delta.assets.isEmpty())
        assertTrue(delta.folded.isEmpty())
        assertEquals(setOf("note-frame"), delta.removedNotes)
        assertEquals(cleared, origin.with(delta))
        assertTrue(origin.with(delta).captures.isEmpty())
    }

    @Test
    fun explicitCaptureIdentityOverridesLegacyProjection() {
        val legacy = Note("frame", "Old", 1.0, 2.0, 100.0, 80.0)
        val replacement = legacy.toCapture().copy(title = "Edited capture")
        val comment = Capture.Comment("remark:node", "Edited question", world, author)
        val placement = Placement(notes = listOf(legacy), remarks = mapOf("node" to "Old question"), capture = listOf(replacement, comment))
        assertEquals(listOf(replacement, comment), placement.captures)
        assertEquals(listOf(legacy), placement.notes)
        assertEquals("Old question", placement.remarks["node"])
    }

    @Test
    fun changeSetReplayKeepsTopologySeparateAndReopensExactPresentation() {
        val origin = Placement(cards = mapOf("node" to at))
        val next = origin.copy(capture = allCaptures(), assets = mapOf(image.id to image), folded = setOf("scope"))
        val part = GraphPart(changes = emptyList(), layout = next.since(origin))
        val set = ChangeSet(id = "presentation", title = "Capture only", revision = 3, savedAt = savedAt(0L), graph = part)
        val decoded = ChangeSet.decode(set.encode()).graph!!
        val shape = GraphShape(emptyList(), emptyList(), emptyList(), emptyList())
        val replay = shape.replay(decoded)
        assertTrue(replay.conflicts.isEmpty())
        assertEquals(shape, replay.shape)
        assertEquals(next, origin.with(decoded.layout!!))
        assertEquals(at, origin.with(decoded.layout!!).cards["node"])
    }

    @Test
    fun anUnchangedPlacementHasNoCaptureOrFoldDelta() {
        val origin = origin()
        assertEquals(Placement(), origin.since(origin))
        assertEquals(origin, origin.with(Placement()))
    }

    private fun origin() = Placement(
        cards = mapOf("node" to at),
        notes = listOf(Note("note-frame", "Frame", 1.0, 2.0, 100.0, 80.0)),
        remarks = mapOf("node" to "Question"),
        bends = mapOf("wire" to listOf(at)),
        marks = mapOf(1 to Mark(0.0, 0.0, 800.0, 600.0)),
        capture = listOf(Capture.Text("text", "Text", world, author), Capture.Box("removed", 30.0, 40.0, world)),
        assets = mapOf("image" to image, "spare" to image.copy(id = "spare")),
        folded = setOf("scope"),
    )

    private fun allCaptures(): List<Capture> = listOf(
        Capture.Note("note", "Context", 120.0, 80.0, world, author),
        Capture.Text("text", "Text", CaptureAnchor.Node("node", at, at, "Node")),
        Capture.Box("box", 20.0, 30.0, CaptureAnchor.Row("node", "row", at, at, "Row")),
        Capture.Diamond("diamond", 20.0, 30.0, CaptureAnchor.Port("node", "port", at, at, "Port")),
        Capture.Ellipse("ellipse", 20.0, 30.0, CaptureAnchor.Wire("wire", at, at, "Wire")),
        Capture.Freehand("freehand", listOf(at, Spot(20.0, 25.0)), CaptureAnchor.Frame("frame", at, at, "Frame")),
        Capture.Arrow("arrow", world, CaptureAnchor.Node("other", at, at, "Missing node"), listOf(at)),
        Capture.Line("line", world, CaptureAnchor.Frame("frame", at, at, "Frame"), listOf(at)),
        Capture.Frame("frame", "Frame", 200.0, 160.0, world, parent = "parent", tint = 17),
        Capture.Image("image", "image", 640.0, 480.0, world, alt = "Evidence"),
        Capture.Comment("comment", "Question", world, author, replyTo = "earlier-comment"),
    )
}
