package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Note as LegacyNote
import dev.shibasis.reaktor.blueprint.editor.Spot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureValueTest {
    private val author = CaptureAuthor("(codex, xhigh, designer)", "bpo-designer", 1)
    private val start = CaptureAnchor.World(Spot(-42.25, 18.5))
    private val end = CaptureAnchor.Port("telemetry", "targets", Spot(8.0, 4.0), Spot(320.0, 160.0), "Retained service telemetry · targets")

    @Test
    fun everyCaptureVariantRoundTripsWithStableDiscriminators() {
        val captures: List<Capture> = listOf(
            Capture.Note("note", "Inspect coverage\nKeep the exact port", 260.0, 120.0, start, author),
            Capture.Text("text", "A question, still presentation", start, author),
            Capture.Box("box", 200.0, 80.0, start, author, 0.25),
            Capture.Diamond("diamond", 100.0, 100.0, start, author),
            Capture.Ellipse("ellipse", 160.0, 80.0, start, author),
            Capture.Freehand("freehand", listOf(Spot(0.0, 0.0), Spot(10.5, -5.25), Spot(32.0, 24.0)), start, author),
            Capture.Arrow("arrow", start, end, listOf(Spot(140.0, 80.0)), author),
            Capture.Line("line", end, start, author = author),
            Capture.Frame("frame", "Kernel findings", 800.0, 400.0, start, author, parent = "outer"),
            Capture.Image("image", "screenshot", 640.0, 360.0, start, author, "Observed coverage panel"),
            Capture.Comment("comment", "Which edge was instrumented?", end, author, replyTo = "question"),
        )
        val encoded = Json.encodeToString(captures)
        assertEquals(captures, Json.decodeFromString<List<Capture>>(encoded))
        for (kind in listOf("note", "text", "box", "diamond", "ellipse", "freehand", "arrow", "line", "frame", "image", "comment")) {
            assertTrue("\"type\":\"$kind\"" in encoded)
        }
    }

    @Test
    fun anchorsKeepExactTargetsAndLastWorldPositionsEvenWhenTheTargetIsAbsent() {
        val offset = Spot(12.5, -8.0)
        val fallback = Spot(-100.0, 240.5)
        val anchors: List<CaptureAnchor> = listOf(
            start,
            CaptureAnchor.Node("removed-node", offset, fallback, "Last known node"),
            CaptureAnchor.Row("source", "chapter:21", offset, fallback, "Chapter 22"),
            end,
            CaptureAnchor.Wire("removed-wire", offset, fallback, "targets → targets"),
            CaptureAnchor.Frame("island", offset, fallback, "KernelGraph"),
        )
        val restored = Json.decodeFromString<List<CaptureAnchor>>(Json.encodeToString(anchors))
        assertEquals(anchors, restored)
        val unattached = restored[1] as CaptureAnchor.Node
        assertEquals(fallback, unattached.fallback)
        assertEquals("removed-node", unattached.node)
        assertEquals("Last known node", unattached.label)
    }

    @Test
    fun arrowsRetainTwoDifferentAnchorsAndTheirAuthorship() {
        val capture: Capture = Capture.Arrow("proposal", end, CaptureAnchor.Node("work-item", Spot(0.0, 0.0), Spot(500.0, 180.0), "Measure coverage"), author = author)
        val restored = Json.decodeFromString<Capture>(Json.encodeToString(capture)) as Capture.Arrow
        assertEquals(end, restored.anchor)
        assertEquals("work-item", (restored.end as CaptureAnchor.Node).node)
        assertEquals(author, restored.author)
        assertEquals(1L, restored.author?.profileRevision)
    }

    @Test
    fun mediaIsReferencedAndCommentsRetainTheirReplyIdentity() {
        val assets = listOf(CaptureAsset("screenshot", "journal/assets/local-screenshot", "image/png"))
        val encoded = Json.encodeToString(assets)
        assertEquals(assets, Json.decodeFromString<List<CaptureAsset>>(encoded))
        assertFalse("data:" in encoded)
        val reply: Capture = Capture.Comment("reply", "I will check the exact route.", start, author, "question")
        assertEquals(reply, Json.decodeFromString<Capture>(Json.encodeToString(reply)))
    }

    @Test
    fun legacyNotesRemainFramesWithTheirOriginalBoundsParentAndTint() {
        val legacy = Json.decodeFromString<LegacyNote>("""{"id":"inner","text":"Exact source name","x":-32.5,"y":80.25,"width":720.0,"height":480.0,"tint":42,"parent":"outer"}""")
        val capture = legacy.toCapture()
        assertEquals(legacy.id, capture.id)
        assertEquals(legacy.text, capture.title)
        assertEquals(legacy.width, capture.width)
        assertEquals(legacy.height, capture.height)
        assertEquals(CaptureAnchor.World(Spot(legacy.x, legacy.y)), capture.anchor)
        assertEquals(legacy.parent, capture.parent)
        assertEquals(legacy.tint, capture.tint)
        assertNull(capture.author)
        assertEquals(capture, Json.decodeFromString<Capture>(Json.encodeToString<Capture>(capture)))
    }

    @Test
    fun theOldDefaultedNoteDoesNotGainInventedAuthorshipOrParentage() {
        val legacy = Json.decodeFromString<LegacyNote>("""{"id":"old","text":"Earlier frame","x":0.0,"y":0.0,"width":320.0,"height":180.0}""")
        val capture = legacy.toCapture()
        assertNull(capture.author)
        assertNull(capture.parent)
        assertEquals(0L, capture.tint)
    }
}
