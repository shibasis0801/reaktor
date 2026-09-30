package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Note(val title: String, val body: String = "")

@Serializable
private data class Reminder(val at: Long)

private class Ticking(private var now: Long = 1_000L) : TimestampProvider {
    override fun getTimestamp(): Long = now
    fun advance(by: Long = 1_000L) {
        now += by
    }
}

class IndexedDbObjectDatabaseTest {
    private fun freshName() = "test-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun database(name: String = freshName(), clock: TimestampProvider = Ticking()) =
        IndexedDbObjectDatabase(name, timestampProvider = clock, factory = indexedDB)

    @Test
    fun aStoredObjectComesBackEqualButRebuilt() = runTest {
        val db = database()
        val note = Note("The plan", "Two quarters.")

        db.put("notes", "plan", note, Note.serializer())
        val read = db.get("notes", "plan", Note::class, Note.serializer())

        assertEquals(note, read?.value)
        assertTrue(note !== read?.value)
    }

    @Test
    fun whatWasWrittenSurvivesReopeningTheDatabase() = runTest {
        val name = freshName()
        val first = database(name)
        first.put("auth", "session", Note("token"), Note.serializer())
        first.close()

        val reopened = database(name)

        assertEquals("token", reopened.get("auth", "session", Note::class, Note.serializer())?.value?.title)
    }

    @Test
    fun aMissingKeyReadsAsNothing() = runTest {
        assertNull(database().get("notes", "absent", Note::class, Note.serializer()))
    }

    @Test
    fun aModelThatCannotBeReadBackSaysSo() = runTest {
        val db = database()
        db.put("things", "one", Note("A note"), Note.serializer())

        assertFailsWith<UnreadableObjectException> {
            db.get("things", "one", Reminder::class, Reminder.serializer())
        }
    }

    @Test
    fun oneUnreadableRowDoesNotCostTheOthers() = runTest {
        val db = database()
        db.put("mixed", "a", Reminder(1), Reminder.serializer())
        db.put("mixed", "b", Note("readable"), Note.serializer())

        val notes = db.getAll("mixed", Note::class, Note.serializer())

        assertEquals(listOf("readable"), notes.map { it.value.title })
    }

    @Test
    fun aStoreOnlyAnswersForItselfInWriteOrder() = runTest {
        val clock = Ticking()
        val db = database(clock = clock)
        db.put("notes", "second", Note("kept later"), Note.serializer())
        clock.advance()
        db.put("drafts", "one", Note("elsewhere"), Note.serializer())
        clock.advance()
        db.put("notes", "first", Note("kept"), Note.serializer())

        val titles = db.getAll("notes", Note::class, Note.serializer()).map { it.value.title }

        assertEquals(listOf("kept later", "kept"), titles)
    }

    @Test
    fun anUpdateKeepsWhenTheRowFirstArrived() = runTest {
        val clock = Ticking()
        val db = database(clock = clock)

        db.put("notes", "plan", Note("first"), Note.serializer())
        clock.advance()
        val stored = db.put("notes", "plan", Note("second"), Note.serializer())

        assertEquals("second", stored.value.title)
        assertEquals(1_000L, stored.createdAt)
        assertEquals(2_000L, stored.updatedAt)
    }

    @Test
    fun deletingAndClearingLeaveNothingBehind() = runTest {
        val db = database()
        db.put("notes", "one", Note("one"), Note.serializer())
        db.put("notes", "two", Note("two"), Note.serializer())
        db.put("other", "three", Note("three"), Note.serializer())

        db.delete("notes", "one")
        assertNull(db.get("notes", "one", Note::class, Note.serializer()))

        db.clear("notes")
        assertEquals(emptyList(), db.getAll("notes", Note::class, Note.serializer()))
        assertEquals(1, db.getAll("other", Note::class, Note.serializer()).size)

        db.clear()
        assertEquals(emptyList(), db.exportRaw())
    }

    @Test
    fun anUnreadableDocumentIsSetAsideRatherThanDestroyed() = runTest {
        val db = database()
        db.put("things", "one", Note("A note"), Note.serializer())

        val setAside = assertNotNull(db.quarantine("things", "one"))

        assertNull(db.get("things", "one", Reminder::class, Reminder.serializer()))
        assertEquals(listOf(setAside), db.exportRaw("things").map { it.key })
    }

    @Test
    fun anExportCanBeImportedIntoAnotherDatabase() = runTest {
        val source = database()
        source.put("notes", "a", Note("alpha"), Note.serializer())
        source.put("notes", "b", Note("beta"), Note.serializer())
        val exported: List<RawObject> = source.exportRaw()

        val target = database()
        target.importRaw(exported)

        assertEquals(
            listOf("alpha", "beta"),
            target.getAll("notes", Note::class, Note.serializer()).map { it.value.title },
        )
    }

    @Test
    fun aRuntimeWithoutIndexedDbFailsLoudly() = runTest {
        val db = IndexedDbObjectDatabase(freshName(), factory = null)

        assertFailsWith<IndexedDbUnavailableException> { db.ready() }
    }
}
