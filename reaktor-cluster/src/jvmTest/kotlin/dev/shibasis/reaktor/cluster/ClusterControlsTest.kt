package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.cluster.build.RouterFixture
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ClusterControlsTest {
    @Test fun graphServiceInspectsAndStopsAnOwnedBuild() = RouterFixture.use { router ->
        val job = router.local("owned-build")
        job.supervised {
            runBlocking {
                ClusterRuntime(ClusterReader(router.state), controls = ClusterControls(router.state)).use { runtime ->
                    assertTrue(runtime.api.inspectJob(ClusterJobRequest(job.run)).actions.stop.supported)
                    assertEquals("Cancelled", runtime.api.stopJob(ClusterJobMutation(job.run, job.execution)).status)
                }
            }
        }
    }

    @Test fun changedExecutionsAndUnsupportedActionsNeverRequestAStop() = RouterFixture.use { router ->
        val job = router.local("owned-build")
        job.supervised {
            val controls = ClusterControls(router.state)
            val changed = assertFailsWith<IllegalArgumentException> { controls.stop(ClusterJobMutation(job.run, "execution-changed")) }
            assertContains(changed.message.orEmpty(), "Execution changed")
            val resume = assertFailsWith<IllegalArgumentException> { controls.resume(ClusterJobMutation(job.run, job.execution)) }
            assertContains(resume.message.orEmpty(), "Terminal resume")
            assertFalse(job.stopRequested)
        }
    }

    @Test fun invalidIdentifiersAreRejectedBeforeAnythingRuns() = RouterFixture.use { router ->
        var calls = 0
        val controls = ClusterControls(router.state, ClusterCommands { _, _ -> calls++; error("Must not run") })
        for (id in listOf("../other", "--help", "build; kill", "", "job.json")) {
            assertFailsWith<IllegalArgumentException> { controls.inspect(ClusterJobRequest(id)) }
        }
        assertFailsWith<IllegalArgumentException> { controls.stop(ClusterJobMutation("owned-build", "../execution")) }
        assertEquals(0, calls)
    }

    @Test fun unreadableRouterStateIsAnUnknownBuildWithNoActions() = RouterFixture.use { router ->
        val missing = ClusterControls(router.state).inspect(ClusterJobRequest("missing-build"))
        assertEquals("Unknown", missing.status)
        assertFalse(missing.actions.stop.supported)
        assertFalse(missing.actions.resume.supported)
        assertContains(missing.message.orEmpty(), "missing-build.json")
        Files.delete(router.state.resolve("config.json"))
        assertContains(ClusterControls(router.state).inspect(ClusterJobRequest("missing-build")).message.orEmpty(), "config.json")
    }
}
