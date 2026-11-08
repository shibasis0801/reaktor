package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.EchoRuntime
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentWorkspaceOwnerLockTest {
    private val scratch = Files.createTempDirectory(Path.of("/tmp"), "rkl")
    private val root = scratch.resolve("workspace").toFile().apply { mkdirs() }
    private val directory = scratch.resolve("agents")

    @AfterTest fun clean() { scratch.toFile().deleteTreeSafely(within = File("/tmp")) }

    @Test fun clientsInTheOwnersProcessLeaveItsLockHeld() = runBlocking {
        AgentWorkspaceConnection.open(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime())).use { owner ->
            assertTrue(owner.ownsService)
            AgentWorkspaceConnection.open(root, directory, allowStart = false).use { client -> assertFalse(client.ownsService) }
            AgentWorkspaceConnection.open(root, directory).use { client -> assertFalse(client.ownsService) }
            assertFalse(lockableElsewhere(directory.resolve("owner.lock")))
        }
        assertTrue(lockableElsewhere(directory.resolve("owner.lock")))
    }

    @Test fun attachOnlyClientsDoNotNeedWriteAccessToTheLiveOwnersLock() = runBlocking {
        AgentWorkspaceConnection.open(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime())).use { owner ->
            val clientDirectory = Files.createSymbolicLink(scratch.resolve("client-agents"), directory)
            val lock = directory.resolve("owner.lock")
            val permissions = Files.getPosixFilePermissions(lock)
            val discovery = directory.resolve("owner.json")
            val endpoint = Files.readAllBytes(discovery)
            val modified = Files.getLastModifiedTime(discovery)
            Files.setPosixFilePermissions(lock, setOf(OWNER_READ))
            try {
                AgentWorkspaceConnection.open(root, clientDirectory, allowStart = false,
                    discover = { error("An attach-only client must not discover runtimes") }).use { client ->
                    assertFalse(client.ownsService)
                    assertEquals(owner.incarnation, client.incarnation)
                    assertEquals(root.canonicalPath, client.info().workspaceRoot)
                }
                assertContentEquals(endpoint, Files.readAllBytes(discovery))
                assertEquals(modified, Files.getLastModifiedTime(discovery))
                assertEquals(setOf(OWNER_READ), Files.getPosixFilePermissions(lock))
            } finally {
                Files.setPosixFilePermissions(lock, permissions)
            }
        }
    }

    @Test fun attachOnlyClientsRefuseAnUnlockedReadOnlyOwnerRecord() {
        val discovery = directory.resolve("owner.json")
        val endpoint = AgentWorkspaceConnection.open(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime())).use {
            Files.readAllBytes(discovery)
        }
        Files.write(discovery, endpoint)
        val lock = directory.resolve("owner.lock")
        val permissions = Files.getPosixFilePermissions(lock)
        val modified = Files.getLastModifiedTime(discovery)
        Files.setPosixFilePermissions(lock, setOf(OWNER_READ))
        try {
            assertFailsWith<IllegalStateException> {
                AgentWorkspaceConnection.open(root, directory, allowStart = false,
                    discover = { error("An attach-only client must not start an owner") })
            }
            assertContentEquals(endpoint, Files.readAllBytes(discovery))
            assertEquals(modified, Files.getLastModifiedTime(discovery))
            assertEquals(setOf(OWNER_READ), Files.getPosixFilePermissions(lock))
        } finally {
            Files.setPosixFilePermissions(lock, permissions)
        }
    }

    private fun lockableElsewhere(file: Path): Boolean {
        val probe = scratch.resolve("Probe.java")
        Files.writeString(probe, """
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;
            public class Probe {
                public static void main(String[] arguments) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.WRITE)) {
                        System.exit(channel.tryLock() == null ? 1 : 0);
                    }
                }
            }
        """.trimIndent())
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(java, probe.toString(), file.toString()).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() == 0
    }
}
