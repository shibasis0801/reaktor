package dev.shibasis.reaktor.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthArchitectureTest {
    private val root: File = repoRoot()
    private val authRoot = File(root, "reaktor-auth/src/jvmMain/kotlin/dev/shibasis/reaktor/auth")

    @Test
    fun jvmAuthRuntimeUsesContextualPackages() {
        listOf(
            "api",
            "db",
            "jwt",
            "runtime",
            "runtime/nodes",
            "runtime/ports",
            "services",
            "spring",
        ).forEach { path ->
            assertTrue(File(authRoot, path).isDirectory, "missing auth layer: $path")
        }

        val legacyJvmGraphFiles = File(authRoot, "graph")
            .ktSources()
            .map { it.relativeTo(root).path }

        assertTrue(
            legacyJvmGraphFiles.isEmpty(),
            "JVM auth runtime belongs under auth/runtime, not the generic auth/graph package:\n$legacyJvmGraphFiles",
        )
    }

    @Test
    fun runtimeGraphOwnsCompositionWithNodeFactories() {
        val source = source("runtime/AuthRuntimeGraph.kt")

        assertTrue("class AuthRuntimeGraph(" in source)
        assertTrue(": Graph(" in source, "AuthRuntimeGraph must be a real graph, not a DTO around a graph.")
        assertFalse("data class AuthRuntimeGraph" in source)

        listOf(
            "Node(::AuthAuditNode)",
            "Node(::AuthRepositoryNode)",
            "Node(::AuthJwtNode)",
            "Node(::AuthPersonalTokenNode)",
            "Node(::AuthSessionLifecycleNode)",
            "Node(::AuthServiceAccountNode)",
            "Node(::LoginInteractorNode)",
            "Node(::AuthPatNode)",
            "Node(::AuthTokenGrantNode)",
            "Node(::AuthSessionNode)",
            "Node(::AuthHttpServiceNode)",
            "Node(::AuthAppServiceNode)",
        ).forEach { expected ->
            assertTrue(expected in source, "runtime graph should compose $expected")
        }

        assertFalse("attach(" in source, "Use Graph.Node(...) so creation, attach, lifecycle, and wiring stay together.")
        assertTrue("autoWire()" in source)
        assertTrue("requireFullyWired()" in source)
    }

    @Test
    fun runtimeInputsFlowThroughGraphDependencies() {
        val graph = source("runtime/AuthRuntimeGraph.kt")
        val support = source("runtime/AuthRuntimeSupport.kt")
        val repositories = source("runtime/nodes/AuthRepositoryNode.kt")
        val jwt = source("runtime/nodes/AuthJwtNode.kt")

        assertTrue("singleton<ExposedAdapter>(AUTH_EXPOSED_ADAPTER_DEPENDENCY)" in graph)
        assertTrue("singleton<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)" in graph)
        assertTrue("AuthRuntimeScopeBuilder(scope).configure()" in support)
        assertTrue("dependency<ExposedAdapter>(AUTH_EXPOSED_ADAPTER_DEPENDENCY)" in repositories)
        assertTrue("dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)" in jwt)
        assertFalse("AuthRepositoryNode(this, adapter)" in graph)
        assertFalse("AuthJwtNode(this, config)" in graph)
    }

    @Test
    fun springIsOnlyAFrameworkBoundary() {
        val spring = source("spring/AuthSpringConfiguration.kt")
        assertTrue("SpringDependencyAdapter(applicationContext)" in spring)

        val springLeaks = authRoot
            .ktSources()
            .filterNot { it.relativeTo(authRoot).path.startsWith("spring/") }
            .filter { "org.springframework" in it.readText() }
            .map { it.relativeTo(root).path }

        assertTrue(
            springLeaks.isEmpty(),
            "Spring imports belong in auth/spring only:\n$springLeaks",
        )
    }

    @Test
    fun springSecurityKeepsInfrastructureProbesPublic() {
        val security = source("spring/DefaultSecurityConfig.kt")

        assertTrue("authorize(\"/\", permitAll)" in security)
        assertTrue("authorize(\"/status\", permitAll)" in security)
        assertTrue("authorize(\"/auth/anonymous\", permitAll)" in security)
        assertTrue("authorize(\"/_graph/**\", permitAll)" in security)
        assertTrue("authorize(\"/actuator/health/**\", permitAll)" in security)
    }

    @Test
    fun lowLevelBearerTransportStaysCentralized() {
        val allowed = setOf(
            "reaktor-auth/src/commonMain/kotlin/dev/shibasis/reaktor/auth/transport/AuthTransport.kt",
        )
        val offenders = listOf(
            File(root, "reaktor-auth/src/commonMain/kotlin/dev/shibasis/reaktor/auth"),
            File(root, "reaktor-auth/src/jvmMain/kotlin/dev/shibasis/reaktor/auth"),
        )
            .flatMap { it.ktSources() }
            .filter { source ->
                val relative = source.relativeTo(root).path
                relative !in allowed && source.readText().hasRawBearerTransport()
            }

        assertTrue(
            offenders.isEmpty(),
            "Low-level Authorization/Bearer parsing and formatting belongs in auth/transport:\n${offenders.relativeList()}",
        )
    }

    private fun source(path: String): String =
        File(authRoot, path).readText()

    private fun File.ktSources(): List<File> =
        if (!exists()) emptyList() else walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun String.hasRawBearerTransport(): Boolean =
        contains("\"Authorization\"") || contains("\"Bearer ")

    private fun List<File>.relativeList(): String =
        joinToString("\n") { it.relativeTo(root).path }

    private fun repoRoot(): File {
        var current = File(".").canonicalFile
        while (current.parentFile != null) {
            if (File(current, "reaktor-auth/build.gradle.kts").exists()) {
                return current
            }
            current = current.parentFile
        }
        error("Could not find Reaktor repository root from ${File(".").canonicalPath}")
    }
}
