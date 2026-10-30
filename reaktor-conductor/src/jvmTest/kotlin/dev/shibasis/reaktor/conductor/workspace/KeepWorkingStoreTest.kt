package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import kotlin.test.*

class KeepWorkingStoreTest {
    private val me = BoardActor(BoardActorKind.Agent, "Falcon", "codex:falcon", "agent:codex:falcon")
    private val plan = WorkPlan("hangar:launch", "Launch", items = listOf(PlanItem("one", "One", status = PlanStatus.Doing, by = me)),
        createdBy = BoardActor(BoardActorKind.Person, "Owner"), createdAt = 1, updatedAt = 1)

    @Test fun bindingAndLastNudgeSurviveRestartWithoutChangingAnotherThread() = fixture { directory ->
        val store = KeepWorkingStore(directory) { 17 }
        store.configure("claude:tower", plan.thread, true)
        val seat = store.configure(me.session!!, plan.thread, false)
        assertTrue(store.decide(seat, me, plan, PlanNext(), "resume").block)
        val restarted = KeepWorkingStore(directory)
        assertEquals(1, restarted.read(me.session!!)!!.nudges)
        assertEquals(17, restarted.read(me.session!!)!!.lastNudgeAt)
        assertTrue(restarted.read("claude:tower")!!.paused)
        assertFalse(Files.list(directory).use { it.anyMatch { path -> path.fileName.toString().startsWith(".keep-working-") } })
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("keep-working.json"))))
        }
    }

    @Test fun twoUnchangedNudgesEndAndARealStatusChangeResetsTheLimit() = fixture { directory ->
        val store = KeepWorkingStore(directory)
        store.configure(me.session!!, plan.thread, false)
        fun stop(value: WorkPlan = plan) = store.decide(store.read(me.session!!)!!, me, value, PlanNext(), "resume")
        assertTrue(stop().block)
        assertTrue(stop(plan.copy(revision = 10)).block)
        assertFalse(stop().block)
        assertFalse(stop().block)
        val changed = plan.copy(items = plan.items + PlanItem("finished", "Finished", status = PlanStatus.Done))
        assertTrue(stop(changed).block)
        assertEquals(1, store.read(me.session!!)!!.nudges)
    }

    @Test fun pauseOrRebindingWinsOverAnInflightPlanRead() = fixture { directory ->
        val store = KeepWorkingStore(directory)
        val seat = store.configure(me.session!!, plan.thread, false)
        store.configure(me.session!!, plan.thread, true)
        assertFalse(store.decide(seat, me, plan, PlanNext(), "resume").block)
        assertTrue(store.read(me.session!!)!!.paused)
        val active = store.configure(me.session!!, plan.thread, false)
        store.configure(me.session!!, "hangar:other", false)
        assertFalse(store.decide(active, me, plan, PlanNext(), "resume").block)
        assertEquals("hangar:other", store.read(me.session!!)!!.plan)
    }

    @Test fun stopMissingPlanMissingIdentityAndQuotaPauseAllowTheEnd() = fixture { directory ->
        val store = KeepWorkingStore(directory)
        val seat = store.configure(me.session!!, plan.thread, false)
        assertFalse(store.decide(seat, me, plan, PlanNext(), "stop now").block)
        assertFalse(store.decide(seat, me, null, PlanNext(), "resume").block)
        assertFalse(store.decide(seat, me.copy(agent = null), plan, PlanNext(), "resume").block)
        assertFalse(store.decide(seat, me, plan, PlanNext(reason = "Paused", quota = "Operator paused this quota pool"), "resume").block)
        assertEquals(0, store.read(me.session!!)!!.nudges)
    }

    @Test fun invalidReferencesAndCorruptRegistryCannotBecomeBindings() = fixture { directory ->
        val store = KeepWorkingStore(directory)
        assertFailsWith<IllegalArgumentException> { store.configure("../../other", plan.thread, false) }
        assertNull(store.read(me.session!!))
        Files.writeString(directory.resolve("keep-working.json"), "broken")
        assertFails { store.configure(me.session!!, plan.thread, false) }
        assertEquals("broken", Files.readString(directory.resolve("keep-working.json")))
    }

    private fun fixture(test: (java.nio.file.Path) -> Unit) {
        val directory = Files.createTempDirectory("keep-working")
        try { test(directory) } finally { directory.toFile().deleteTreeSafely(within = directory.parent.toFile()) }
    }
}
