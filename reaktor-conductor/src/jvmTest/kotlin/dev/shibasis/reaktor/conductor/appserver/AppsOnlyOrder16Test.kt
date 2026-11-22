package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.*
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AppsOnlyOrder16Test {
    @Test fun automaticCompareAndCouncilNeverChooseTheProductionBatchRuntimes() {
        val root = Files.createTempDirectory("apps-only-order16").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            SupervisedProcessExecutor().use { executor ->
                AgentWorkspace(root, root.resolve("plane").toPath(), AgentRuntimes.interactive(scope),
                    scope = scope, discover = { ProviderCapability(it) }, batchRuntimes = AgentRuntimes.batch(executor)).use { workspace ->
                    val useBatch = AgentWorkspace::class.java.getDeclaredMethod("useBatch", AgentSubmission::class.java).apply { isAccessible = true }
                    val choices = listOf(AgentCollaboration.Compare, AgentCollaboration.Council).filter { kind ->
                        val request = AgentSubmission("apps-only-${kind.name}", RuntimeKind.Codex, "Invented comparison",
                            collaboration = kind, partner = AgentPartner(RuntimeKind.Gemini), transport = AgentTransport.Automatic)
                        useBatch.invoke(workspace, request) as Boolean
                    }
                    assertEquals(emptyList(), choices, "These turns must use visible app sessions or refuse")
                }
            }
        } finally {
            scope.cancel()
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
