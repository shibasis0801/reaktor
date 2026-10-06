package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.infra.InfrastructureSession
import dev.shibasis.reaktor.tooling.infra.MissingCredential
import dev.shibasis.reaktor.tooling.infra.ServiceTokenClient
import dev.shibasis.reaktor.tooling.infra.ServiceTokenSource
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import kotlin.test.*

class ServiceTokenClientTest {
    @Test fun partialCredentialsReportMissingKeysWithoutMakingAnAuthenticationRequest() {
        val root = Files.createTempDirectory("missing-worker-credentials-").toFile()
        val missing = root.resolve("absent.env")
        try {
            InfrastructureSession().use { session ->
                val source = ServiceTokenSource("https://fixture.invalid/token", "fixture", "PROD", missing.path,
                    "CLIENT_ID", "CLIENT_SECRET", emptyList())
                val failure = assertFailsWith<MissingCredential> {
                    ServiceTokenClient(session).token(source, mapOf("CLIENT_ID" to "fixture-private-value"))
                }
                assertEquals(missing.path, failure.file)
                assertEquals(setOf("CLIENT_SECRET"), failure.keys)
                assertFalse(requireNotNull(failure.message).contains("fixture-private-value"))
                assertContains(requireNotNull(failure.message), missing.path)
            }
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
}
