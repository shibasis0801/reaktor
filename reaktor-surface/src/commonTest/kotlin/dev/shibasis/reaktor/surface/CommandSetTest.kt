package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandSetTest {
    private val back = Command(CommandId("back"), "Back", Chord.Of(KeyName.LeftBracket, primary = true))
    private val backByWord = Command(CommandId("back-by-word"), "Back", Chord.Of(KeyName.Left, alt = true))
    private val graph = Command(CommandId("view-graph"), "Graph", Chord.Of(KeyName.Digit1, primary = true), Mark.Choice(true))
    private val logs = Command(CommandId("view-logs"), "Logs", Chord.Of(KeyName.Digit2, primary = true), Mark.Choice(false))
    private val stop = Command(CommandId("stop"), "Stop", Chord.Of(KeyName.Period, primary = true), availability = Availability.Unavailable("Nothing is running"))
    private val app = Command(CommandId("app-bestbuds"), "BestBuds", mark = Mark.Choice(true))

    private val set = CommandSet(
        commands = listOf(back, backByWord, graph, logs, stop, app),
        menus = listOf(
            CommandMenu(
                "File",
                listOf(CommandEntry.Group("Application", listOf(CommandEntry.Item(app.id))), CommandEntry.Separator),
            ),
            CommandMenu(
                "View",
                listOf(CommandEntry.Caption("MODES"), CommandEntry.Item(graph.id), CommandEntry.Item(logs.id), CommandEntry.Separator),
            ),
            CommandMenu("Navigate", listOf(CommandEntry.Item(back.id))),
            CommandMenu("Run", listOf(CommandEntry.Item(stop.id), CommandEntry.Item(CommandId("missing")))),
        ),
    )

    @Test
    fun pathsFollowTheMenusThenListKeyboardOnlyCommands() {
        assertEquals(
            listOf(
                CommandPath(listOf("File", "Application"), app),
                CommandPath(listOf("View"), graph),
                CommandPath(listOf("View"), logs),
                CommandPath(listOf("Navigate"), back),
                CommandPath(listOf("Run"), stop),
                CommandPath(emptyList(), backByWord),
            ),
            set.paths(),
        )
    }

    @Test
    fun aCommandInTwoMenusHasTwoPaths() {
        val twice = set.copy(menus = set.menus + CommandMenu("Go", listOf(CommandEntry.Item(back.id))))
        assertEquals(listOf(listOf("Navigate"), listOf("Go")), twice.paths().filter { it.command == back }.map { it.path })
    }

    @Test
    fun findReturnsTheCommandForAStroke() {
        assertEquals(graph, set.find(KeyStroke(KeyName.Digit1, meta = true), KeyConvention.Mac))
        assertEquals(graph, set.find(KeyStroke(KeyName.Digit1, control = true), KeyConvention.Pc))
        assertEquals(backByWord, set.find(KeyStroke(KeyName.Left, alt = true), KeyConvention.Mac))
        assertNull(set.find(KeyStroke(KeyName.Digit1, control = true), KeyConvention.Mac))
    }

    @Test
    fun findSkipsUnavailableCommands() {
        assertNull(set.find(KeyStroke(KeyName.Period, meta = true), KeyConvention.Mac))
        val available = set.copy(commands = set.commands.map { if (it.id == stop.id) it.copy(availability = Availability.Available) else it })
        assertEquals(stop.id, available.find(KeyStroke(KeyName.Period, meta = true), KeyConvention.Mac)?.id)
    }
}
