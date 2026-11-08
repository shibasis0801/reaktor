package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ClusterSettingsTest {
    @Test fun readUsesTransportDefaultsAndDoesNotCreateFiles() = temporary { state ->
        Files.writeString(state.resolve("config.json"), """{"host":"worker","unrelated":true}""")
        val snapshot = ClusterSettings(state).read()
        assertEquals(6, snapshot.localMaxWorkers)
        assertEquals(1, snapshot.localSlots)
        assertEquals(2, snapshot.workerSlots)
        assertEquals(6, snapshot.additionalSlotMaxWorkers)
        assertTrue(snapshot.revision.matches(Regex("[0-9a-f]{64}")))
        assertEquals(listOf("config.json"), Files.list(state).use { it.map { path -> path.fileName.toString() }.toList() })
    }

    @Test fun reviewedUpdatePreservesOtherConfigurationAndFilePermissions() = temporary { state ->
        val config = state.resolve("config.json")
        Files.writeString(config, """{"host":"worker","projects":["bestbuds"],"forward_env":["EXAMPLE_NAME"],"extra":{"enabled":true},"local_max_workers":12}""")
        val permissions = PosixFilePermissions.fromString("rw-rw----")
        Files.setPosixFilePermissions(config, permissions)
        val before = Json.parseToJsonElement(Files.readString(config)).jsonObject
        val settings = ClusterSettings(state)
        val loaded = settings.read()
        val updated = settings.update(ClusterBuildSettingsUpdate(loaded.revision, 8, 3, 4))
        assertEquals(Triple(8, 3, 4), Triple(updated.localMaxWorkers, updated.workerSlots, updated.additionalSlotMaxWorkers))
        assertNotEquals(loaded.revision, updated.revision)
        assertEquals(updated, settings.read())
        assertEquals(permissions, Files.getPosixFilePermissions(config))
        val after = Json.parseToJsonElement(Files.readString(config)).jsonObject
        for (key in listOf("host", "projects", "forward_env", "extra")) assertEquals(before[key], after[key], key)
        assertTrue(Files.list(state).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".cluster-settings-") } })
    }

    @Test fun staleRevisionCannotOverwriteANewerReviewedChange() = temporary { state ->
        Files.writeString(state.resolve("config.json"), "{}")
        val settings = ClusterSettings(state)
        val loaded = settings.read()
        settings.update(ClusterBuildSettingsUpdate(loaded.revision, 8, 2, 4))
        val beforeConflict = Files.readString(state.resolve("config.json"))
        val conflict = assertFailsWith<ClusterSettingsConflictException> {
            settings.update(ClusterBuildSettingsUpdate(loaded.revision, 16, 3, 8))
        }
        assertTrue(conflict.message.orEmpty().contains("Refresh"))
        assertEquals(beforeConflict, Files.readString(state.resolve("config.json")))
    }

    @Test fun invalidConcurrencyIsRejectedWithoutChangingConfiguration() = temporary { state ->
        val config = state.resolve("config.json")
        Files.writeString(config, "{}")
        val settings = ClusterSettings(state)
        val revision = settings.read().revision
        for ((local, slots, additional) in listOf(Triple(0, 2, 6), Triple(65, 2, 6), Triple(6, 0, 6),
            Triple(6, 4, 6), Triple(6, 2, 0), Triple(6, 2, 65))) {
            assertFailsWith<IllegalArgumentException> { settings.update(ClusterBuildSettingsUpdate(revision, local, slots, additional)) }
        }
        assertEquals("{}", Files.readString(config))
    }

    @Test fun concurrentEditorsCannotBothCommitTheSameRevision() = temporary { state ->
        Files.writeString(state.resolve("config.json"), "{}")
        val revision = ClusterSettings(state).read().revision
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(4, 8).map { workers -> executor.submit(Callable {
                start.await()
                runCatching { ClusterSettings(state).update(ClusterBuildSettingsUpdate(revision, workers, 2, 6)) }
            }) }
            start.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.exceptionOrNull() is ClusterSettingsConflictException })
            assertTrue(ClusterSettings(state).read().localMaxWorkers in setOf(4, 8))
        } finally { executor.shutdownNow() }
    }

    @Test fun settingsReadAndUpdateUseTheTypedServiceBoundary() = temporary { state ->
        Files.writeString(state.resolve("config.json"), "{}")
        val reader = ClusterReader(state, HostProbe(ClusterCommands { _, _ -> CommandResult(0, "hw.ncpu: 14") }))
        ClusterRuntime(reader).use { runtime -> runBlocking {
            val loaded = runtime.api.settings(Unit)
            val updated = runtime.api.updateSettings(ClusterBuildSettingsUpdate(loaded.revision, 12, 3, 4, localSlots = 2))
            assertEquals(updated, runtime.api.settings(Unit))
            assertEquals(listOf(12, 4), reader.status().slots.filter { it.host == "local" }.map { it.workers })
            assertFailsWith<ClusterSettingsConflictException> {
                runtime.api.updateSettings(ClusterBuildSettingsUpdate(loaded.revision, 8, 2, 6))
            }
            val legacy = runtime.api.updateSettings(ClusterBuildSettingsUpdate(updated.revision, 8, 2, 6))
            assertEquals(2, legacy.localSlots)
            assertFailsWith<IllegalArgumentException> {
                runtime.api.updateSettings(ClusterBuildSettingsUpdate(legacy.revision, 8, 2, 6, localSlots = 3))
            }
            runtime.api.updateSettings(ClusterBuildSettingsUpdate(legacy.revision, 8, 2, 6, localSlots = 1))
            assertEquals(listOf(8), reader.status().slots.filter { it.host == "local" }.map { it.workers })
        } }
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val state = Files.createTempDirectory("cluster-settings-").toRealPath()
        try { block(state) } finally { state.toFile().deleteRecursively() }
    }
}
