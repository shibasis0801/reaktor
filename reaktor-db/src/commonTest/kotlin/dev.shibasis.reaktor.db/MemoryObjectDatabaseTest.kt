package dev.shibasis.reaktor.db

import dev.shibasis.reaktor.db.core.MemoryObjectDatabase
import dev.shibasis.reaktor.db.core.TimestampProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Note(val title: String, val body: String = "")

@Serializable
private data class Reminder(val at: Long)

private class Ticking(private var now: Long = 1_000L) : TimestampProvider {
    override fun getTimestamp(): Long = now
    fun advance(by: Long = 1_000L) { now += by }
}

class MemoryObjectDatabaseTest {

    @Test
    fun aStoredObjectIsSerialisedOnTheWayInAndBuiltAgainOnTheWayOut() = runTest {
        val db = MemoryObjectDatabase()
        val note = Note("The plan", "Two quarters.")

        db.put("notes", "plan", note, Note.serializer())
        val read = db.get("notes", "plan", Note::class, Note.serializer())

        assertEquals(note, read?.value)
        // Equal, but not the same instance: this is the point of the class. A fake that handed back
        // what it was given would prove the code stores things, which was never in doubt, and would
        // hide every model that cannot survive a round trip.
        assertTrue(note !== read?.value)
    }

    @Test
    fun aModelThatCannotBeReadBackSaysSoRatherThanReturningNothing() = runTest {
        val db = MemoryObjectDatabase()
        db.put("things", "one", Note("A note"), Note.serializer())

        // A row written by one shape and read by another is a different thing from a row that is
        // not there, and only the caller knows which of the two it can carry on from.
        assertFailsWith<UnreadableObjectException> {
            db.get("things", "one", Reminder::class, Reminder.serializer())
        }
    }

    @Test
    fun oneUnreadableRowDoesNotCostTheOthers() = runTest {
        val db = MemoryObjectDatabase()
        db.put("mixed", "a", Reminder(1), Reminder.serializer())
        db.put("mixed", "b", Note("readable"), Note.serializer())

        val notes = db.getAll("mixed", Note::class, Note.serializer())

        assertEquals(listOf("readable"), notes.map { it.value.title })
    }

    @Test
    fun aStoreOnlyAnswersForItself() = runTest {
        val db = MemoryObjectDatabase()
        db.put("notes", "one", Note("kept"), Note.serializer())
        db.put("drafts", "one", Note("elsewhere"), Note.serializer())

        assertEquals(listOf("kept"), db.getAll("notes", Note::class, Note.serializer()).map { it.value.title })
        assertEquals(1, db.size("drafts"))
    }

    @Test
    fun anUpdateKeepsWhenTheRowFirstArrived() = runTest {
        val clock = Ticking()
        val db = MemoryObjectDatabase(timestampProvider = clock)

        db.put("notes", "plan", Note("first"), Note.serializer())
        clock.advance()
        db.put("notes", "plan", Note("second"), Note.serializer())

        val stored = db.get("notes", "plan", Note::class, Note.serializer())
        assertEquals("second", stored?.value?.title)
        assertEquals(1_000L, stored?.createdAt)
        assertEquals(2_000L, stored?.updatedAt)
    }

    @Test
    fun deletingAndClearingLeaveNothingBehind() = runTest {
        val db = MemoryObjectDatabase()
        db.put("notes", "one", Note("one"), Note.serializer())
        db.put("notes", "two", Note("two"), Note.serializer())

        db.delete("notes", "one")
        assertNull(db.get("notes", "one", Note::class, Note.serializer()))
        assertEquals(1, db.size("notes"))

        db.clear("notes")
        assertEquals(0, db.size("notes"))
    }
}
