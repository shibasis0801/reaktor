package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class TestFilterTest {
    @TempDir lateinit var directory: File

    @Test fun `filters with explicit test classes run what they name and fail when nothing matches`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"filterFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.jvm") }
            repositories { mavenCentral() }
            dependeasy {
                jvm(bytecode = 21)
                testClasses()
            }
            dependencies { testImplementation(kotlin("test-junit")) }
        """.trimIndent())
        directory.resolve("src/test/kotlin/fixture").mkdirs()
        directory.resolve("src/test/kotlin/fixture/ChangeChecksTest.kt").writeText("""
            package fixture
            import kotlin.test.*
            class ChangeChecksTest {
                @Test fun first() {}
                @Test fun second() {}
            }
        """.trimIndent())
        directory.resolve("src/test/kotlin/fixture/OtherTests.kt").writeText("""
            package fixture
            import kotlin.test.*
            class OtherTests { @Test fun other() {} }
        """.trimIndent())
        val results = directory.resolve("build/test-results/test")
        fun run(filter: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("test", "--rerun", "--tests", filter)
        fun cases() = results.listFiles().orEmpty().filter { it.name.startsWith("TEST-") }
            .associate { it.name.removePrefix("TEST-").removeSuffix(".xml") to Regex("<testcase ").findAll(it.readText()).count() }
        for (filter in listOf("*ChangeChecksTest*", "*.ChangeChecksTest", "ChangeChecksTest", "fixture.Change*")) {
            run(filter).build()
            assertEquals(mapOf("fixture.ChangeChecksTest" to 2), cases(), filter)
        }
        run("*ChangeChecksTest.second").build()
        assertEquals(mapOf("fixture.ChangeChecksTest" to 1), cases())
        assertContains(run("*NoSuchTest*").buildAndFail().output, "No tests found for given includes")
    }
}
