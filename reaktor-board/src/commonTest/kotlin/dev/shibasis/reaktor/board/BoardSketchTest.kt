package dev.shibasis.reaktor.board

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardSketchTest {
    private val lanes = listOf(
        BoardLane("next", "To do"),
        BoardLane("doing", "In progress", BoardInk.Busy),
        BoardLane("done", "Done", BoardInk.Ok, create = false, review = true),
    )
    private val words = BoardWords(one = "mission", many = "missions")
    private val items = listOf(
        BoardItem("a", "Map the learning journey", "done"),
        BoardItem("b", "Design the first session", "doing", after = listOf("a")),
        BoardItem("c", "Run a small pilot", "next", after = listOf("b")),
        BoardItem("d", "Share what we learned", "next", after = listOf("b")),
    )
    private val board = Board(lanes, items, words)
    private val positions = items.ranked()

    private fun box(id: String, x: Double, y: Double, type: String = "rectangle") = SketchMark(id, type, x, y, 200.0, 100.0)
    private fun label(id: String, box: String, text: String) = SketchMark(id, "text", 0.0, 0.0, text = text, container = box)
    private fun arrow(id: String, x: Double, y: Double, dx: Double, dy: Double, start: String? = null, end: String? = null) =
        SketchMark(id, "arrow", x, y, start = start, end = end, points = listOf(BoardPoint(0.0, 0.0), BoardPoint(dx, dy)))

    private val drawing = listOf(
        box("r1", 1300.0, 80.0), label("t1", "r1", "Write the report"),
        box("r2", 1300.0, 300.0, "ellipse"), label("t2", "r2", "Book the room \n now"),
        box("r3", 1700.0, 80.0),
        arrow("a1", 1400.0, 180.0, 0.0, 120.0, start = "r1", end = "r2"), label("l1", "a1", "then"),
        arrow("a2", 900.0, 150.0, 450.0, -30.0),
        arrow("a3", 1350.0, 350.0, -1250.0, -220.0),
        arrow("a4", 2000.0, 2000.0, 100.0, 100.0),
        arrow("a5", 700.0, 170.0, 200.0, 0.0),
        SketchMark("n1", "text", 1300.0, 600.0, text = "Remember the snacks"),
    )

    @Test
    fun labelledBoxesBecomeCardsAndJoinedArrowsBecomePrerequisites() {
        val reading = board.read(drawing, positions)
        assertEquals("next", reading.lane)
        assertEquals(listOf(SketchCard("sketch-1", "Write the report", BoardPoint(1300.0, 80.0)), SketchCard("sketch-2", "Book the room now", BoardPoint(1300.0, 300.0))), reading.cards)
        assertEquals(listOf(SketchLink("c", "sketch-1"), SketchLink("sketch-1", "sketch-2")), reading.links)
        assertEquals(listOf(SketchRefusal("a5", "“Run a small pilot” already waits on “Design the first session”."), SketchRefusal("a3", "That arrow would create a cycle.")), reading.refused)
        assertEquals(1, reading.loose)
        assertEquals(setOf("r1", "t1", "r2", "t2", "a1", "a2", "l1"), reading.consumed)
        assertEquals(emptyList(), reading.notes)
        assertEquals(BoardEdit.Sketch("next", reading.cards, reading.links), reading.edit)
        assertEquals("Added 2 missions, 2 prerequisites. 2 arrows stay on the sketch: “Run a small pilot” already waits on “Design the first session”. 1 arrow does not join two missions.",
            board.summary(reading))
    }

    @Test
    fun freeTextBecomesANoteOnlyWhereTheBoardKeepsNotes() {
        val reading = board.copy(notes = true).read(drawing, positions)
        assertEquals(listOf(SketchNote("Remember the snacks", BoardPoint(1300.0, 600.0))), reading.notes)
        assertTrue("n1" in reading.consumed)
        assertTrue("n1" !in board.read(drawing, positions).consumed)
    }

    @Test
    fun limitsLocksAndEmptySketchesExplainThemselves() {
        val crowded = listOf(box("r1", 1300.0, 80.0), label("t1", "r1", "Write the report"),
            arrow("a1", 100.0, 150.0, 1250.0, 0.0), arrow("a2", 500.0, 160.0, 850.0, 0.0))
        val limited = board.copy(limit = 1).read(crowded, positions)
        assertEquals(listOf(SketchLink("a", "sketch-1")), limited.links)
        assertEquals(listOf(SketchRefusal("a2", "A mission can have up to 1 prerequisite.")), limited.refused)
        val locked = board.copy(locked = "This board is closed.").read(drawing, positions)
        assertNull(locked.edit)
        assertEquals("This board is closed.", board.summary(locked))
        val empty = board.read(listOf(box("r3", 0.0, 0.0)), positions)
        assertNull(empty.edit)
        assertEquals("Nothing to turn into missions yet. Draw a box with a title inside, or an arrow between two missions.", board.summary(empty))
    }

    @Test
    fun referencesNeverCollideWithExistingKeys() {
        val crowded = board.copy(items = items + BoardItem("sketch-9", "Taken", "next"))
        assertEquals("_sketch-1", crowded.read(drawing, positions).cards.first().ref)
    }

    @Test
    fun arrowsFindACardThroughAnyOfItsShapesOrJustOutsideItsEdge() {
        val report = listOf(box("r1", 1300.0, 80.0), label("t1", "r1", "Write the report"))
        val bound = board.read(report + arrow("a1", 0.0, 0.0, 10.0, 10.0, start = "${BoardIds.card("d")}:shadow", end = "r1"), positions)
        assertEquals(listOf(SketchLink("d", "sketch-1")), bound.links)
        val beside = board.read(report + arrow("a1", 1154.0, 100.0, 140.0, 0.0), positions)
        assertEquals(listOf(SketchLink("c", "sketch-1")), beside.links)
        assertNull(BoardIds.cardOf("dependency:${BoardIds.card("a")}:${BoardIds.card("b")}"))
    }

    @Test
    fun sketchesCreatePrerequisitesFirstAndSurviveTheBridge() {
        val sketch = BoardEdit.Sketch("next", listOf(SketchCard("s1", "Later", BoardPoint(0.0, 0.0)), SketchCard("s2", "First", BoardPoint(0.0, 0.0))),
            listOf(SketchLink("s2", "s1"), SketchLink("c", "s1")), listOf(SketchNote("Why", BoardPoint(1.0, 2.0))))
        assertEquals(listOf("s2", "s1"), sketch.ordered().map { it.ref })
        assertEquals(listOf("s2", "c"), sketch.prerequisites("s1"))
        val json = Json { encodeDefaults = true }
        val change = BoardChange(sketch, revision = 3)
        assertEquals(change, json.decodeFromString(BoardChange.serializer(), json.encodeToString(BoardChange.serializer(), change)))
        val receipt = BoardReceipt(4, created = mapOf("s1" to "T7"))
        assertEquals(receipt, json.decodeFromString(BoardReceipt.serializer(), json.encodeToString(BoardReceipt.serializer(), receipt)))
    }
}
