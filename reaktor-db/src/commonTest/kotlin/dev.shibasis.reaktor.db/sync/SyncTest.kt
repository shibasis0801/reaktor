package dev.shibasis.reaktor.db.sync

import dev.shibasis.reaktor.db.core.MemoryObjectDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Position(val ms: Long, val played: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTest {
    private var time = 1_000L
    private val server = MemorySyncLog()
    private val account = "account-1"

    private inner class Device {
        val database = MemoryObjectDatabase()
        var sync = Sync(database) { time }
        var positions = sync.store<Position>(STORE)
        val transport = server.transport(account)

        suspend fun position(key: String) = database.get(STORE, key, Position::class, Position.serializer())?.value

        /** A new process over the same storage. */
        fun restart() {
            sync = Sync(database) { time }
            positions = sync.store(STORE)
        }

        // syncOnce without connect still needs the account joined, as connect would.
        suspend fun round() = sync.syncOnce(transport)
    }

    private fun TestScope.connect(device: Device, transport: SyncTransport = device.transport) {
        device.sync.connect(transport, account, backgroundScope, settleMs = 0)
        runCurrent()
    }

    @Test
    fun anHlcSortsAsTextExactlyAsItCompares() {
        val readings = listOf(
            Hlc(1_000, 0, "b"), Hlc(1_000, 0, "a"), Hlc(999, 7, "z"), Hlc(1_000, 12, "a"),
            Hlc(1_788_782_400_000, 3, "f00d"), Hlc(1_000, 2, "a"),
        )
        assertEquals(readings.sorted(), readings.sortedBy { it.toString() })
        readings.forEach { assertEquals(it, Hlc.parse(it.toString())) }
        assertNull(Hlc.parse("yesterday"))
    }

    @Test
    fun theClockNeverRunsBackwardsAndMovesPastWhatItHears() {
        var wall = 5_000L
        val clock = HlcClock("me") { wall }
        val first = clock.tick()
        wall = 4_000 // the device's clock is put back
        val second = clock.tick()
        assertTrue(second > first)

        val ahead = Hlc(90_000, 4, "other")
        clock.receive(ahead)
        assertTrue(clock.tick() > ahead)
    }

    @Test
    fun aChangeOnOneDeviceReachesTheOther() = runTest {
        val phone = Device()
        val tablet = Device()
        val heard = mutableListOf<RemoteChange<Position>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tablet.positions.remote.collect { heard += it } }
        connect(phone); connect(tablet)

        phone.positions.put("episode-1", Position(30_000))
        phone.round(); tablet.round()

        assertEquals(Position(30_000), tablet.position("episode-1"))
        assertEquals(listOf(RemoteChange("episode-1", Position(30_000))), heard)
    }

    @Test
    fun theLaterWriteWinsWhicheverArrivesLast() = runTest {
        val phone = Device()
        val tablet = Device()
        connect(phone); connect(tablet)

        phone.positions.put("episode-1", Position(10_000)) // earlier, but reaches the server last
        time += 60_000
        tablet.positions.put("episode-1", Position(50_000))
        tablet.round()
        phone.round()
        tablet.round()

        assertEquals(Position(50_000), phone.position("episode-1"))
        assertEquals(Position(50_000), tablet.position("episode-1"))
    }

    @Test
    fun aDeletionIsNotUndoneByAnOlderWriteArrivingLate() = runTest {
        val phone = Device()
        val tablet = Device()
        connect(phone); connect(tablet)
        tablet.positions.put("show", Position(0))
        tablet.round(); phone.round()

        time += 1_000
        phone.positions.put("show", Position(5)) // made offline
        time += 1_000
        tablet.positions.delete("show")
        tablet.round()
        phone.round()

        assertNull(phone.position("show"))
        assertNull(tablet.position("show"))
    }

    @Test
    fun aChangeFromElsewhereIsNotSentBack() = runTest {
        val phone = Device()
        val tablet = Device()
        connect(phone); connect(tablet)
        phone.positions.put("episode-1", Position(30_000))
        phone.round(); tablet.round()

        assertEquals(0, tablet.sync.pending())
    }

    @Test
    fun whatWasHereBeforeSigningInReachesTheAccount() = runTest {
        val phone = Device()
        phone.database.put(STORE, "episode-1", Position(42_000), Position.serializer())
        connect(phone)
        advanceTimeBy(1)

        val stored = server.snapshot(account).values.single()
        assertEquals("episode-1", stored.key)
        assertTrue(stored.value!!.contains("42000"))
    }

    @Test
    fun aWriteMadeOfflineIsSentWhenTheConnectionReturns() = runTest {
        var reachable = false
        val phone = Device()
        connect(phone, server.transport(account, reachable = { reachable }))

        phone.positions.put("episode-1", Position(30_000))
        runCurrent()
        assertIs<SyncStatus.Retrying>(phone.sync.status.value)
        assertEquals(1, phone.sync.pending())

        reachable = true
        advanceTimeBy(Sync.MAX_RETRY_MS)
        runCurrent()

        assertIs<SyncStatus.UpToDate>(phone.sync.status.value)
        assertEquals(0, phone.sync.pending())
        assertEquals(1, server.snapshot(account).size)
    }

    @Test
    fun aRefusedCredentialStopsUntilNudged() = runTest {
        var signedIn = false
        val phone = Device()
        connect(phone, server.transport(account, signedIn = { signedIn }))
        assertEquals(SyncStatus.SignedOut, phone.sync.status.value)

        advanceTimeBy(Sync.MAX_RETRY_MS * 4)
        assertEquals(SyncStatus.SignedOut, phone.sync.status.value)

        signedIn = true
        phone.sync.nudge()
        runCurrent()
        assertIs<SyncStatus.UpToDate>(phone.sync.status.value)
    }

    @Test
    fun aWriteMadeWhileAPushIsInFlightIsNotDropped() = runTest {
        val phone = Device()
        connect(phone)
        phone.sync.disconnect()
        phone.positions.put("episode-1", Position(10_000))

        val racing = object : SyncTransport {
            var raced = false
            override suspend fun push(ops: List<SyncOp>) {
                if (!raced) {
                    raced = true
                    time += 1_000
                    phone.positions.put("episode-1", Position(20_000))
                }
                server.push(account, ops)
            }

            override suspend fun pull(after: String?, limit: Int) = server.pull(account, after, limit)
        }
        phone.sync.syncOnce(racing)

        assertEquals(0, phone.sync.pending())
        assertTrue(server.snapshot(account).values.single().value!!.contains("20000"))
    }

    @Test
    fun theOutboxAndTheCursorSurviveARestart() = runTest {
        val phone = Device()
        val tablet = Device()
        connect(phone); connect(tablet)
        phone.sync.disconnect()
        phone.positions.put("episode-1", Position(30_000))

        phone.restart()
        assertEquals(1, phone.sync.pending())
        phone.round()
        tablet.round()
        assertEquals(Position(30_000), tablet.position("episode-1"))

        tablet.sync.disconnect()
        tablet.restart()
        var pulled = 0
        val counting = object : SyncTransport {
            override suspend fun push(ops: List<SyncOp>) = tablet.transport.push(ops)
            override suspend fun pull(after: String?, limit: Int) =
                tablet.transport.pull(after, limit).also { pulled += it.ops.size }
        }
        tablet.sync.syncOnce(counting)
        // It asked from the cursor it kept, not from the beginning.
        assertEquals(0, pulled)
        assertEquals(Position(30_000), tablet.position("episode-1"))
    }

    private companion object {
        const val STORE = "positions"
    }
}
