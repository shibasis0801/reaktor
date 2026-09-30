package dev.shibasis.reaktor.tooling

import java.nio.file.Files
import kotlin.test.*

class KotlinSourceIndexTest {
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
        } finally { root.deleteRecursively() }
    }
}
