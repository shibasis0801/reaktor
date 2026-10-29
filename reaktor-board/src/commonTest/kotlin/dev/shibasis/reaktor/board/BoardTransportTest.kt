package dev.shibasis.reaktor.board

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardTransportTest {
    @Test fun pagesKeepOneSnapshotAcrossChangesAndPreserveUnicode() = runTest {
        val initial = BoardSnapshot(Board(emptyList(), List(200) { BoardItem("$it", "漢🚀\"\n".repeat(100), "done") }), 1)
        val snapshots = MutableStateFlow(initial)
        val source = object : BoardSource {
            override val changes = snapshots
            override suspend fun read() = snapshots.value
            override suspend fun change(request: BoardChange) = BoardReceipt()
            override suspend fun select(request: BoardSelect) = Unit
        }
        val transport = BoardTransport(source)
        val first = transport.read(BoardRead())
        assertTrue(first.count > 1)
        snapshots.value = initial.copy(revision = 2)
        val newer = transport.read(BoardRead())
        val assembly = BoardAssembly()
        assertNull(assembly.accept(first))
        var assembled: BoardSnapshot? = null
        for (part in 1 until first.count) assembled = assembly.accept(transport.read(BoardRead(first.token, part)))
        assertEquals(initial, assembled)
        assertNull(assembly.accept(newer))
        assertNull(assembly.accept(transport.read(BoardRead(first.token, 1))))
        assertNull(assembly.accept(transport.read(BoardRead(newer.token, 1))))
        val events = transport.changes.take(newer.count).toList()
        assertEquals(newer, events.first())
        var changed: BoardSnapshot? = null
        events.forEach { changed = assembly.accept(it) }
        assertEquals(snapshots.value, changed)
    }
}
