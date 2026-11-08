package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClusterLocationsTest {
    @Test fun existingWorkspaceStateTakesPrecedenceOverRelocatedState() = temporary { root ->
        val workspace = Files.createDirectories(root.resolve("dev"))
        val legacy = Files.createDirectories(workspace.resolve(".remote-dev"))
        Files.createDirectories(root.resolve("other/.remote-dev"))
        assertEquals(legacy, ClusterLocations.state(workspace))
    }

    @Test fun relocatedSiblingStateIsUsedWithoutRecreatingWorkspaceState() = temporary { root ->
        val workspace = Files.createDirectories(root.resolve("dev"))
        val relocated = Files.createDirectories(root.resolve("other/.remote-dev"))
        assertEquals(relocated, ClusterLocations.state(workspace))
        assertFalse(Files.exists(workspace.resolve(".remote-dev")))
    }

    @Test fun configuredStateTakesPrecedenceOverAnEmptyRecreatedDirectory() = temporary { root ->
        val workspace = Files.createDirectories(root.resolve("dev"))
        val legacy = Files.createDirectories(workspace.resolve(".remote-dev"))
        val relocated = Files.createDirectories(root.resolve("other/.remote-dev"))
        Files.writeString(relocated.resolve("config.json"), "{}")
        assertEquals(relocated, ClusterLocations.state(workspace))
        Files.writeString(legacy.resolve("config.json"), "{}")
        assertEquals(legacy, ClusterLocations.state(workspace))
    }

    @Test fun missingStateRetainsTheLegacyPathWithoutCreatingIt() = temporary { root ->
        val workspace = root.resolve("explicit-workspace")
        assertEquals(workspace.resolve(".remote-dev"), ClusterLocations.state(workspace))
        assertFalse(Files.exists(workspace))
        assertFalse(Files.exists(root.resolve("other")))
    }

    @Test fun relocatedStateKeepsTheRetainedWorkspaceInMachineObservations() = temporary { root ->
        val workspace = Files.createDirectories(root.resolve("dev"))
        val state = Files.createDirectories(root.resolve("other/.remote-dev"))
        Files.writeString(state.resolve("config.json"), """{"host":"worker","local_max_workers":12}""")
        assertEquals(workspace, ClusterLocations.workspace(state))
        assertEquals(workspace, ClusterLocations.workspace(workspace.resolve(".remote-dev")))
        val reader = ClusterReader(state, HostProbe(ClusterCommands { _, _ -> CommandResult(0, "hw.ncpu: 14") }))
        assertEquals(workspace.toString(), reader.status().slots.first { it.host == "local" }.tree)
    }

    @Test fun relocatedTemporaryFoldersRemainChargedToTheCombinedBudget() = temporary { root ->
        Files.createDirectories(root.resolve("dev"))
        val state = Files.createDirectories(root.resolve("other/.remote-dev"))
        val home = Files.createDirectories(root.resolve("home"))
        val files = listOf("dev/tmp/current", "other/tmp/moved", "other/temp/moved", "other/.tmp/moved").map { name ->
            root.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, "temporary") }
        }
        val report = NativeStorage(state, home = home, systemRoots = emptyList(),
            scanDirectory = root.resolve("scans"), openOverride = emptySet<Path>() to emptySet(),
            freeOverride = 50_000_000_000).read()
        val pass = report.passes.first { it.name == "temp" }
        assertTrue(pass.roots.containsAll(files.map(Path::toString)))
        assertTrue(pass.bytesBefore.apparent >= 36)
    }

    private inline fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cluster-locations-").toRealPath()
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
