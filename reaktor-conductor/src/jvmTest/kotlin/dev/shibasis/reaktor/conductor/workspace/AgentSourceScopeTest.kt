package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSourceScopeTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "source-scope")
    private fun scope(vararg paths: String) = AgentSourceScope.of(root, paths.toList()) { values ->
        values.map { path -> when {
            path == ":engine" || "engine/" in path -> "workspace/engine"
            path == ":kernel" || "kernel/" in path -> "workspace/kernel"
            else -> "workspace"
        } }
    }

    @Test fun sameModuleFilesCanBeWrittenIndependently() {
        assertFalse(scope("engine/src/A.kt").overlaps(scope("engine/src/B.kt")))
        assertTrue(scope("engine/src/A.kt").overlaps(scope("engine/src/A.kt")))
        assertTrue(scope("engine/src").overlaps(scope("engine/src/A.kt")))
        assertFalse(scope("engine/src").overlaps(scope("engine/srcs/A.kt")))
    }

    @Test fun buildFilesAndExplicitModuleClaimsReserveTheirModule() {
        assertTrue(scope("engine/build.gradle.kts").overlaps(scope("engine/src/B.kt")))
        assertTrue(scope("engine/src/B.kt").overlaps(scope("engine/build.gradle.kts")))
        assertTrue(scope(":engine").overlaps(scope("engine/src/B.kt")))
        assertFalse(scope(":engine").overlaps(scope("kernel/src/B.kt")))
        assertTrue(scope("settings.gradle.kts").overlaps(scope("engine/src/B.kt")))
    }

    @Test fun rootSourceFilesStayExactAndCanonicalPathsMatch() {
        assertFalse(scope("AGENTS.md").overlaps(scope("engine/src/B.kt")))
        assertTrue(scope("AGENTS.md").overlaps(scope(root.resolve("AGENTS.md").path)))
        assertTrue(scope("engine/src/../src/A.kt").overlaps(scope("engine/src/A.kt")))
        assertFalse(scope("engine/src/A.kt").overlaps(scope("kernel/src/A.kt")))
    }

    @Test fun unscopedWritersStillReserveAllSource() {
        assertTrue(scope().overlaps(scope("engine/src/B.kt")))
        assertTrue(scope("*").overlaps(scope("kernel/src/B.kt")))
        assertTrue(scope(".").overlaps(scope("AGENTS.md")))
    }
}
