package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.model.device.CommandRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DeviceFlowKernelTest {
    private data class Run(val flows: List<String>, val hooks: List<String>, val failure: Throwable?)

    private fun run(failing: Set<String> = emptySet(), failCleanup: Boolean = false): Run {
        val root = Files.createTempDirectory("device-flow").toFile().canonicalFile
        try {
            val runs = root.resolve("runs")
            val runner = root.resolve("runner").apply {
                writeText("""
                    #!/bin/sh
                    if [ "${'$'}3" = devices ]; then printf '[{"id":"phone","platform":"android","kind":"device","ready":true}]'; exit 0; fi
                    for argument in "${'$'}@"; do case "${'$'}argument" in *.yaml) flow=${'$'}{argument##*/} ;; esac; done
                    printf '%s\n' "${'$'}flow" >> '$runs'
                    case "${'$'}flow" in ${failing.joinToString("|").ifEmpty { "never" }}) exit 1 ;; esac
                """.trimIndent() + "\n")
                setExecutable(true)
            }
            val flows = listOf("first.yaml", "second.yaml", "third.yaml").map { root.resolve(it).absolutePath }
            val spec = KernelSpec.DeviceFlow("android", "fixture.app", "fixture:app:output", flows,
                root.resolve("contract.json").absolutePath, emptyList(), ToolRef("maestro-runner", "fixture"))
            val execution = DeviceFlowExecution(root, DeviceFlowContract(platform = "android", applicationId = "fixture.app",
                appOutput = spec.appOutput, appFile = null, flows = flows, runner = runner.absolutePath,
                reportDirectory = root.resolve("reports").absolutePath), root.resolve("tmp/runner"), null,
                emptyList(), emptySet(), emptyMap(), listOf("hook"))
            val hooks = mutableListOf<String>()
            val failure = runCatching {
                DeviceFlowKernel.run(spec, execution, { args, _ ->
                    val contract = BuildModelReader.json.decodeFromString<DeviceFlowContract>(File(args[2]).readText())
                    hooks += "${args[1]} ${contract.flows.joinToString(",") { File(it).name }} ${args[3]}"
                    CommandRunner.Result(if (failCleanup && args[1] == "afterFlow") 1 else 0, "", "fixture cleanup failed")
                }, {})
            }.exceptionOrNull()
            return Run(runs.takeIf(File::isFile)?.readLines().orEmpty(), hooks, failure)
        } finally { root.deleteTreeSafely(root.parentFile) }
    }

    @Test fun `a failed flow still runs every remaining flow and keeps the suite red`() {
        val result = run(setOf("first.yaml", "third.yaml"))
        assertEquals(listOf("first.yaml", "second.yaml", "third.yaml"), result.flows)
        assertEquals(2, result.failure?.message?.lines()?.size)
        assertContains(result.failure?.message.orEmpty(), "first.yaml")
        assertContains(result.failure?.message.orEmpty(), "third.yaml")
        assertEquals(listOf("afterFlow first.yaml false", "afterFlow second.yaml true", "afterFlow third.yaml false"),
            result.hooks.filter { it.startsWith("afterFlow") })
        assertEquals("afterSuite first.yaml,second.yaml,third.yaml false", result.hooks.last())
    }

    @Test fun `a passing suite closes successfully`() {
        val result = run()
        assertNull(result.failure)
        assertEquals(3, result.flows.size)
        assertEquals("afterSuite first.yaml,second.yaml,third.yaml true", result.hooks.last())
    }

    @Test fun `failed fixture cleanup stops subsequent flows and closes unsuccessfully`() {
        val result = run(failCleanup = true)
        assertEquals(listOf("first.yaml"), result.flows)
        assertNotNull(result.failure)
        assertEquals("afterSuite first.yaml,second.yaml,third.yaml false", result.hooks.last())
    }
}
