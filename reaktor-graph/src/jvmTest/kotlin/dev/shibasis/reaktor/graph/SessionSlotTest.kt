package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.ui.SessionSlot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSlotTest {
    @Test fun buildsOnlyOnDemandOffMainAndClosesReplacedActivations() = runBlocking {
        val main = newSingleThreadContext("slot-test-main")
        Dispatchers.setMain(main)
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val identity = MutableStateFlow<String?>(null)
        val builds = AtomicInteger()
        val slot = SessionSlot(root, "/session", identity) {
            assertFalse(SwingUtilities.isEventDispatchThread())
            assertFalse(Thread.currentThread().name == "slot-test-main")
            builds.incrementAndGet()
            Activation(root)
        }
        root.attach(slot)
        try {
            slot.start()
            withContext(Dispatchers.Main) { }
            assertEquals(0, builds.get())
            identity.value = "first"
            val first = withTimeout(5_000) { slot.child.first { it != null } } as Activation
            slot.start()
            withContext(Dispatchers.Main) { }
            assertEquals(1, builds.get())
            identity.value = "second"
            val second = withTimeout(5_000) { slot.child.first { it != null && it !== first } } as Activation
            assertSame(second, slot.activeGraph)
            assertEquals(1, first.closes.get())
            slot.close()
            slot.close()
            assertEquals(1, second.closes.get())
        } finally { root.close(); dependencies.close(); Dispatchers.resetMain(); main.close() }
    }

    @Test fun supersededAndClosedBuildersCannotPublishLateActivations() = runBlocking {
        val main = newSingleThreadContext("slot-test-main")
        Dispatchers.setMain(main)
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val identity = MutableStateFlow<String?>("first")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discarded = Activation(root)
        val latest = Activation(root)
        val slot = SessionSlot(root, "/session", identity) { key ->
            if (key == "first") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); discarded } else latest
        }
        root.attach(slot)
        try {
            slot.start()
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { identity.value = "second"; yield() }
            release.countDown()
            withTimeout(5_000) { slot.child.first { it === latest } }
            assertEquals(1, discarded.closes.get())
            assertEquals(0, latest.closes.get())
            slot.close()
            assertEquals(1, latest.closes.get())
        } finally { release.countDown(); root.close(); dependencies.close(); Dispatchers.resetMain(); main.close() }
    }

    @Test fun closeDuringConstructionDisposesTheLateGraph() = runBlocking {
        val main = newSingleThreadContext("slot-test-main")
        Dispatchers.setMain(main)
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discarded = Activation(root)
        val slot = SessionSlot(root, "/session", MutableStateFlow("first")) {
            started.countDown(); check(release.await(5, TimeUnit.SECONDS)); discarded
        }
        root.attach(slot)
        try {
            slot.start()
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            slot.close()
            release.countDown()
            withTimeout(5_000) { while (discarded.closes.get() == 0) delay(10) }
            assertEquals(1, discarded.closes.get())
            assertNull(slot.child.value)
        } finally { release.countDown(); root.close(); dependencies.close(); Dispatchers.resetMain(); main.close() }
    }

    @Test fun failedActivationIsObservableAndStartRetriesTheSameIdentity() = runBlocking {
        val main = newSingleThreadContext("slot-test-main")
        Dispatchers.setMain(main)
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val attempts = AtomicInteger()
        val slot = SessionSlot(root, "/session", MutableStateFlow("first")) {
            if (attempts.incrementAndGet() == 1) error("private failure")
            Activation(root)
        }
        root.attach(slot)
        try {
            slot.start()
            withTimeout(5_000) { slot.failure.first { it != null } }
            assertNull(slot.child.value)
            slot.start()
            val activation = withTimeout(5_000) { slot.child.first { it != null } } as Activation
            assertNull(slot.failure.value)
            assertEquals(2, attempts.get())
            slot.close()
            assertEquals(1, activation.closes.get())
        } finally { root.close(); dependencies.close(); Dispatchers.resetMain(); main.close() }
    }

    @Test fun anUnknownInitialIdentityPreservesPreviewUntilARealSessionChange() = runBlocking {
        val main = newSingleThreadContext("slot-test-main")
        Dispatchers.setMain(main)
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val identity = MutableStateFlow<String?>(null)
        val slot = SessionSlot(root, "/session", identity) { Activation(root) }
        root.attach(slot)
        try {
            slot.preview("preview")
            val preview = slot.child.value as Activation
            slot.start()
            withContext(Dispatchers.Main) { }
            assertSame(preview, slot.child.value)
            assertEquals(0, preview.closes.get())
            identity.value = "signed-in"
            val activation = withTimeout(5_000) { slot.child.first { it != null && it !== preview } } as Activation
            assertEquals(1, preview.closes.get())
            identity.value = null
            withTimeout(5_000) { slot.child.first { it == null } }
            assertEquals(1, activation.closes.get())
        } finally { root.close(); dependencies.close(); Dispatchers.resetMain(); main.close() }
    }

    private class Activation(parent: Graph) : Graph(parentGraph = parent, dependencyAdapter = parent.diAdapter) {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet(); super.close() }
    }
}
