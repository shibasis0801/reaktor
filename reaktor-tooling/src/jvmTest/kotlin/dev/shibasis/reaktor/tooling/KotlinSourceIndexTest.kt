package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import java.nio.file.Files
import kotlin.test.*

class KotlinSourceIndexTest {
    @Test fun remoteSimpleNamesKeepPackageAmbiguityAndPlatformDeclarations() {
        val root = Files.createTempDirectory("reaktor-remote-source-test").toFile()
        try {
            root.resolve("Worker.kt").writeText("package example.worker\nprivate class CloudflareMessagingService")
            root.resolve("Common.kt").writeText("package example\nexpect class Shared")
            root.resolve("Jvm.kt").writeText("package example\nactual class Shared")
            root.resolve("First.kt").writeText("package first\nclass Repository")
            root.resolve("Second.kt").writeText("package second\nclass Repository")
            val index = KotlinSourceIndex.read(listOf(root))
            assertEquals("example.worker.CloudflareMessagingService", index.find("CloudflareMessagingService").single().qualifiedName)
            assertEquals(2, index.find("Shared").size)
            assertTrue(index.find("Repository").isEmpty())
            assertEquals("first.Repository", index.find("first.Repository").single().qualifiedName)
            assertTrue(index.find("wrong.CloudflareMessagingService").isEmpty())
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun exactDeclarationsIgnoreCommentsStringsAndNestedNamesButKeepAmbiguity() {
        val root = Files.createTempDirectory("reaktor-source-test").toFile()
        try {
            root.resolve("A.kt").writeText("""
                package example
                /*
                class Ghost
                */
                class Parent {
                    class Child
                    val text = "class StringGhost {"
                }
                expect class Shared
            """.trimIndent())
            root.resolve("B.kt").writeText("package example\nactual class Shared")
            root.resolve("build").mkdir()
            root.resolve("build/Generated.kt").writeText("package example\nclass Ghost")
            val index = KotlinSourceIndex.read(listOf(root))
            assertEquals(5, index.find("example.Parent").single().line)
            assertEquals(2, index.find("example.Shared").size)
            listOf("Ghost", "Child", "StringGhost").forEach { assertTrue(index.find("example.$it").isEmpty()) }
            assertTrue(index.find("wrong.Parent").isEmpty())
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
}
