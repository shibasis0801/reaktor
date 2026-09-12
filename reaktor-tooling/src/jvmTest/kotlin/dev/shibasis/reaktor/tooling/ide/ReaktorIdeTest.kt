package dev.shibasis.reaktor.tooling.ide

import dev.shibasis.reaktor.tooling.ide.IdeEnvironment
import dev.shibasis.reaktor.tooling.ide.ReaktorIde
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaktorIdeTest {

    @Test
    fun runningAndroidStudioBeatsInstalledIdea() {
        val plan = ReaktorIde.commandPlan(
            file = File("/tmp/Node.kt"),
            line = 42,
            environment = FakeIdeEnvironment(
                executableByCommand = mapOf(
                    "idea" to "/usr/local/bin/idea",
                    "studio" to "/usr/local/bin/studio",
                ),
                runningText = listOf("/Applications/Android Studio.app/Contents/MacOS/studio"),
            ),
        )

        assertEquals(listOf("/usr/local/bin/studio", "--line", "42", "/tmp/Node.kt"), plan.first())
    }

    @Test
    fun frontmostIdeWinsWhenBothAreRunning() {
        val plan = ReaktorIde.commandPlan(
            file = File("/tmp/Node.kt"),
            line = 7,
            environment = FakeIdeEnvironment(
                executableByCommand = mapOf(
                    "idea" to "/usr/local/bin/idea",
                    "studio" to "/usr/local/bin/studio",
                ),
                frontmost = "Android Studio",
                runningText = listOf(
                    "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
                    "/Applications/Android Studio.app/Contents/MacOS/studio",
                ),
            ),
        )

        assertEquals(listOf("/usr/local/bin/studio", "--line", "7", "/tmp/Node.kt"), plan.first())
    }

    @Test
    fun keepsIdeaAsColdStartDefaultWhenNoIdeIsRunning() {
        val plan = ReaktorIde.commandPlan(
            file = File("/tmp/Node.kt"),
            line = 9,
            environment = FakeIdeEnvironment(
                executableByCommand = mapOf(
                    "idea" to "/usr/local/bin/idea",
                    "studio" to "/usr/local/bin/studio",
                ),
            ),
        )

        assertEquals(listOf("/usr/local/bin/idea", "--line", "9", "/tmp/Node.kt"), plan.first())
    }

    @Test
    fun runningStudioFallsBackToMacAppOpenWhenCliIsMissing() {
        val plan = ReaktorIde.commandPlan(
            file = File("/tmp/Node.kt"),
            line = 11,
            environment = FakeIdeEnvironment(
                executableByCommand = mapOf("idea" to "/usr/local/bin/idea"),
                runningText = listOf("/Applications/Android Studio.app/Contents/MacOS/studio"),
            ),
        )

        assertEquals(listOf("open", "-b", "com.google.android.studio", "/tmp/Node.kt"), plan.first())
        assertTrue(plan.last() == listOf("open", "/tmp/Node.kt"))
    }
}

private class FakeIdeEnvironment(
    override val isMac: Boolean = true,
    private val executableByCommand: Map<String, String> = emptyMap(),
    private val frontmost: String? = null,
    private val runningText: List<String> = emptyList(),
) : IdeEnvironment {
    override fun findExecutable(command: String): String? =
        executableByCommand[command]

    override fun frontmostApplicationName(): String? =
        frontmost

    override fun runningProcessText(): List<String> =
        runningText
}
