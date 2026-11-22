package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.EchoRuntime
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

class WorkspaceFixturesTest {
    @Test fun fixtureCapabilitiesNeverComeFromTheInstalledHarnesses() {
        val root = Files.createTempDirectory("workspace-fixture-discovery").toFile()
        try {
            fixtureWorkspace(root, root.toPath().resolve("state"), mapOf(RuntimeKind.Echo to EchoRuntime())).use { workspace ->
                assertNull(workspace.info().capabilities.single().probedAt, "Fixtures must never probe installed harnesses")
            }
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }
}
