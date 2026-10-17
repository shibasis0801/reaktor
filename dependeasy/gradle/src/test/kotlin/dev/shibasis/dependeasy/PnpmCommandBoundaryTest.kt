package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class PnpmCommandBoundaryTest {
    @TempDir lateinit var directory: File

    private fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace")

    @Test fun `component and nested Node commands cannot implicitly rewrite an outdated lock`() {
        directory.resolve("base.json").writeText(File("javascript/tsconfig/base.json").readText())
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"commandBoundary\"")
        directory.resolve("package.json").writeText("""{"name":"fixture-root","private":true}""")
        directory.resolve("pnpm-workspace.yaml").writeText("packages: ['web', 'other']\n")
        val manifest = directory.resolve("web/package.json").apply {
            parentFile.mkdirs()
            writeText("""{"name":"fixture-web","private":true,"scripts":{"check":"node -e \"console.log('command reached')\""}}""")
        }
        directory.resolve("nested.mjs").writeText("""
            import { spawnSync } from 'node:child_process';
            const result = spawnSync('pnpm', ['--dir', 'web', 'run', 'check'], { stdio: 'inherit' });
            if (result.error) throw result.error;
            process.exitCode = result.status ?? 1;
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                javascript("web", "web").check("check")
                nodeScript("nestedCheck", file("nested.mjs")) {
                    dependsOn("pnpmInstall")
                    sourceFiles.from("web/package.json")
                }
            }
            tasks.withType<dev.shibasis.dependeasy.web.WebContractTask>().configureEach {
                baseConfiguration.set(layout.projectDirectory.file("base.json"))
            }
        """.trimIndent())
        runner("pnpmLock").build()
        val initial = runner("webCheck", "nestedCheck").build()
        assertEquals(TaskOutcome.SUCCESS, initial.task(":webCheck")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, initial.task(":nestedCheck")?.outcome)
        val repeat = runner("webCheck", "nestedCheck").build()
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":webCheck")?.outcome)
        val lock = directory.resolve("pnpm-lock.yaml")
        val original = lock.readText()
        manifest.writeText("""{"name":"fixture-web","private":true,"dependencies":{"fixture-other":"workspace:*"},"scripts":{"check":"node -e \"console.log('command reached')\""}}""")
        directory.resolve("other/package.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"fixture-other","private":true}""")
        }
        for (task in listOf("webCheck", "nestedCheck")) {
            val refused = runner(task, "-x", "pnpmInstall").buildAndFail()
            assertContains(refused.output, "ERR_PNPM_VERIFY_DEPS_BEFORE_RUN")
            assertFalse(refused.output.contains("command reached"), refused.output)
            assertEquals(original, lock.readText())
        }
        runner("pnpmLock").build()
        val repaired = runner("webCheck", "nestedCheck").build()
        assertEquals(TaskOutcome.SUCCESS, repaired.task(":webCheck")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, repaired.task(":nestedCheck")?.outcome)
        assertNotEquals(original, lock.readText())
    }
}
