package dev.shibasis.reaktor.board

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardTest {
    private val lanes = listOf(
        BoardLane("next", "To do"),
        BoardLane("doing", "In progress", BoardInk.Busy),
        BoardLane("done", "Done", BoardInk.Ok, create = false, review = true),
    )
    private val words = BoardWords(one = "mission", many = "missions", review = "outcome review")
    private val items = listOf(
        BoardItem("a", "Map the learning journey", "done"),
        BoardItem("b", "Design the first session", "doing", after = listOf("a"), eyebrow = "Manna", badge = "P0", initials = "sh"),
        BoardItem("c", "Run a small pilot", "next", after = listOf("b")),
        BoardItem("d", "Share what we learned", "next", after = listOf("b")),
    )
    private val board = Board(lanes, items, words)

    @Test
    fun ranksFollowDependencies() {
        val at = items.ranked()
        assertEquals(BoardPoint(48.0, 80.0), at["a"])
        assertEquals(BoardPoint(448.0, 80.0), at["b"])
        assertEquals(BoardPoint(848.0, 80.0), at["c"])
        assertEquals(BoardPoint(848.0, 308.0), at["d"])
    }

    @Test
    fun cyclesAreFound() {
        assertTrue(items.wouldCycle("a", "c"))
        assertFalse(items.wouldCycle("c", "a"))
    }

    @Test
    fun problemsFollowTheirOrder() {
        assertEquals("This prerequisite is no longer available.", board.problem(BoardView.Dag, "x", "a"))
        assertEquals("This mission is no longer available.", board.problem(BoardView.Dag, "a", "x"))
        assertEquals("Switch to DAG to edit prerequisites.", board.problem(BoardView.Kanban, "a", "c"))
        assertEquals("This prerequisite is already connected.", board.problem(BoardView.Dag, "b", "c"))
        assertEquals("That connection would create a cycle.", board.problem(BoardView.Dag, "c", "a"))
        assertEquals("Shared", board.problem(BoardView.Dag, "a", "c") { _, _ -> "Shared" })
        assertNull(board.problem(BoardView.Dag, "a", "c"))
        assertEquals("Closed.", board.copy(locked = "Closed.").problem(BoardView.Dag, "a", "c"))
        val readOnly = Board(lanes, items.map { if (it.key == "c") it.copy(editable = false) else it }, words)
        assertEquals("This mission is read only.", readOnly.problem(BoardView.Dag, "a", "c"))
        assertEquals("A mission can have up to 1 prerequisite.", board.copy(limit = 1).problem(BoardView.Dag, "a", "c"))
    }

    @Test
    fun creationNamesTheLanesThatAcceptIt() {
        assertNull(board.creationProblem("next"))
        assertEquals("Create a mission in To do or In progress; completing it requires outcome review.", board.creationProblem("done"))
        assertEquals("Use a title between 1 and 300 characters.", board.titleProblem("  "))
        assertNull(board.titleProblem("Pilot"))
        assertEquals("an item", indefinite("item"))
    }

    @Test
    fun idsEscapeLikeTheBrowser() {
        assertEquals("card:hub%00id", BoardIds.card("hub\u0000id"))
        assertEquals("card:a%20b%C3%A9", BoardIds.card("a bé"))
        assertEquals("dependency:card:a:card:b", BoardIds.edge("a", "b"))
    }

    @Test
    fun wrapKeepsWholeWords() {
        assertEquals("Design the\nfirst\nsession", wrap("Design the first session", 12, 3))
        assertEquals("Design the\nfirst…", wrap("Design the first session again", 11, 2))
    }

    @Test
    fun kanbanOrderKeepsSavedSlotsAndAppendsTheRest() {
        val shown = items
        val at = board.positions(BoardView.Kanban, shown, emptyMap())
        assertEquals(BoardPoint(68.0, 126.0), at["c"])
        assertEquals(BoardPoint(68.0, 326.0), at["d"])
        assertEquals(BoardPoint(432.0, 126.0), at["b"])
        assertEquals(listOf("d", "c"), board.order("next", shown, mapOf("d" to BoardPoint(68.0, 126.0))).map { it.key })
        assertEquals("No prerequisites", board.words.count(0))
    }

    @Test
    fun dagCardsGroupTheirShapesAndShowLaneAndPrerequisites() {
        val at = board.positions(BoardView.Dag, items, emptyMap())
        val groups = board.groups(BoardFrame(items, at, selected = "b")).associateBy { it.id }
        val card = groups.getValue("card:b").shapes.associateBy { it.id }
        assertEquals(BoardBox("card:b", 448.0, 80.0, 300.0, 180.0, "#8370e7", "#ffffff", 1.8), card["card:b"])
        assertEquals(BoardLabel("card:b:code", "MANNA", 470.0, 103.0, 12, "#93a0b5"), card["card:b:code"])
        assertEquals(BoardLabel("card:b:initials", "SH", 701.0, 228.0, 11, "#7868b1"), card["card:b:initials"])
        assertEquals(BoardLabel("card:b:dependencies", "In progress · 1 prerequisite", 470.0, 231.0, 12, "#94a0b5"), card["card:b:dependencies"])
        assertTrue("card:b:halo" in card && "card:c:halo" !in groups.getValue("card:c").shapes.map { it.id })
        assertEquals(listOf("dependency:card:a:card:b", "dependency:card:b:card:c", "dependency:card:b:card:d", "card:a", "card:b", "card:c", "card:d"), groups.keys.toList())
        val status = board.copy(items = items.map { if (it.key == "c") it.copy(footer = "Codex · running the parser checks again") else it })
        assertEquals("Codex · running the parser checks…", (status.scene(BoardFrame(status.items, at)).first { it.id == "card:c:dependencies" } as BoardLabel).text)
    }

    @Test
    fun dagSceneDrawsElbowArrows() {
        val at = board.positions(BoardView.Dag, items, mapOf("c" to BoardPoint(900.0, 400.0)))
        val shapes = board.scene(BoardFrame(items, at)).associateBy { it.id }
        assertEquals(BoardArrow("dependency:card:b:card:c", 748.0, 170.0, 152.0, 320.0, "#b4bad0", ends = BoardEnds("b", "c")), shapes["dependency:card:b:card:c"])
        assertEquals(BoardLabel("card:c:dependencies", "To do · 1 prerequisite", 922.0, 551.0, 12, "#94a0b5"), shapes["card:c:dependencies"])
    }

    @Test
    fun reorderInsertsAtTheIndexAndRenumbersTheLane() {
        val laid = board.positions(BoardView.Kanban, items, emptyMap())
        val moving = items.first { it.key == "b" }
        val placed = board.reorder("next", items, laid, moving, 1)
        assertEquals(BoardPoint(68.0, 126.0), placed["c"])
        assertEquals(BoardPoint(68.0, 326.0), placed["b"])
        assertEquals(BoardPoint(68.0, 526.0), placed["d"])
        assertEquals(BoardPoint(68.0, 526.0), board.reorder("next", items, laid, moving, 99)["b"])
    }

    @Test
    fun budgetKeepsRankedItemsAndDropsDanglingLinks() {
        val many = Board(lanes, (1..400).map { BoardItem("k$it", "Task number $it with a long enough title", if (it % 2 == 0) "done" else "next",
            after = if (it > 1) listOf("k${it - 1}") else emptyList()) }, words)
        val fitted = BoardBudget.fit(BoardSnapshot(many), 20_000) { if (it.lane == "done") 1 else 0 }
        val keys = fitted.board.items.mapTo(HashSet()) { it.key }
        assertTrue(BoardBudget.size(fitted) <= 20_000)
        assertEquals(400, fitted.board.items.size + fitted.hidden)
        assertTrue(fitted.board.items.isNotEmpty() && fitted.board.items.all { it.lane == "next" })
        assertTrue(fitted.board.items.all { item -> item.after.all { it in keys } })
        assertEquals(BoardSnapshot(board), BoardBudget.fit(BoardSnapshot(board)))
    }

    @Test
    fun bridgeMessagesRoundTrip() {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
        val change = BoardChange(BoardEdit.Create("Pilot", "next", after = "b", at = BoardPoint(1.0, 2.0)), revision = 4)
        val text = json.encodeToString(BoardChange.serializer(), change)
        assertTrue("\"type\":\"create\"" in text)
        assertEquals(change, json.decodeFromString(BoardChange.serializer(), text))
        val snapshot = BoardSnapshot(board, 3, BoardView.Kanban, "b", "agent")
        assertEquals(snapshot.board, json.decodeFromString(BoardSnapshot.serializer(), json.encodeToString(BoardSnapshot.serializer(), snapshot)).board)
    }
}
