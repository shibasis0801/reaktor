package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.model.device.CommandRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DeviceExchangeKernelTest {
    private class Exchange(val hooks: List<String>, val runs: List<List<String>>, val failure: Throwable?)

    private fun exchange(vararg failing: String): Exchange {
        val root = Files.createTempDirectory("device-exchange").toFile().canonicalFile
        try {
            val shared = root.resolve("shared").apply { mkdirs() }
            failing.forEach { shared.resolve(it).writeText("") }
            val home = root.resolve("runner-home").apply { resolve("bin").mkdirs(); resolve(".sha256").writeText("fixture") }
            val runner = home.resolve("bin/runner").apply {
                writeText("""
                    #!/bin/sh
                    if [ "${'$'}3" = devices ]; then printf '[{"id":"%s-phone","platform":"%s","kind":"device","ready":true}]' "${'$'}2" "${'$'}2"; exit 0; fi
                    platform=${'$'}2
                    while [ ${'$'}# -gt 0 ]; do
                      case "${'$'}1" in
                        --output) output=${'$'}2; shift ;;
                        -e) case "${'$'}2" in MESSAGE=*) message=${'$'}{2#MESSAGE=} ;; APP_ENV=*) environment=${'$'}{2#APP_ENV=} ;; esac; shift ;;
                        *.yaml) flow=${'$'}{1##*/} ;;
                      esac
                      shift
                    done
                    [ "${'$'}environment" = dev ] || exit 3
                    mkdir -p "${'$'}output"
                    printf '%s|%s|%s|%s\n' "${'$'}platform" "${'$'}flow" "${'$'}message" "${'$'}output" >> '$shared/runs'
                    case ${'$'}flow in
                      receive.yaml)
                        [ -f '$shared/receive' ] && exit 1
                        printf '%s' "${'$'}message" > '$shared/listening'
                        i=0
                        while [ "${'$'}(cat '$shared/inbox' 2>/dev/null)" != "${'$'}message" ]; do i=${'$'}((i + 1)); [ ${'$'}i -gt 50 ] && exit 1; sleep 0.1; done ;;
                      send.yaml)
                        [ -f '$shared/send' ] && exit 1
                        [ "${'$'}(cat '$shared/listening' 2>/dev/null)" = "${'$'}message" ] || exit 2
                        printf '%s' "${'$'}message" > '$shared/inbox' ;;
                    esac
                """.trimIndent() + "\n")
                setExecutable(true)
            }
            val phones = listOf("android", "ios").map { platform ->
                KernelSpec.DeviceFlow(platform, "fixture.app", "fixture:${platform}App:output",
                    listOf("receive.yaml", "send.yaml").map { root.resolve("flows/$it").absolutePath },
                    root.resolve("contracts/$platform.json").absolutePath, emptyList(), ToolRef("maestro-runner", "fixture"))
            }
            val executions = phones.map { phone ->
                DeviceFlowExecution(root, DeviceFlowContract(platform = phone.platform, applicationId = phone.applicationId,
                    appOutput = phone.appOutput, appFile = null, flows = phone.flows, runner = runner.absolutePath,
                    reportDirectory = root.resolve("reports/${phone.platform}").absolutePath),
                    root.resolve("tmp/runner"), null, emptyList(), emptySet(), mapOf("APP_ENV" to "dev"), listOf("hook"))
            }
            val hooks = mutableListOf<String>()
            val failure = runCatching {
                DeviceExchangeKernel.run(KernelSpec.DeviceExchange(phones, "receive.yaml", "send.yaml", "MESSAGE", 1), executions, { arguments, _ ->
                    if (arguments == DeviceTools.xcodebuild()) CommandRunner.Result(0, runner.absolutePath, "") else {
                        val contract = BuildModelReader.json.decodeFromString<DeviceFlowContract>(File(arguments[2]).readText())
                        hooks.add(listOf(arguments[1], contract.platform, contract.flows.joinToString(",") { File(it).name }, arguments[3]).joinToString(" "))
                        CommandRunner.Result(0, "", "")
                    }
                }, {})
            }.exceptionOrNull()
            val runs = shared.resolve("runs").takeIf { it.isFile }?.readLines().orEmpty().map { line ->
                line.split('|').let { (platform, flow, message, output) -> listOf(platform, flow, message, File(output).relativeTo(root).path) }
            }
            return Exchange(hooks, runs, failure)
        } finally { root.deleteTreeSafely(root.parentFile) }
    }

    @Test fun `each phone receives a fresh token from the other while it waits`() {
        val exchange = exchange()
        assertNull(exchange.failure)
        assertEquals(listOf(
            listOf("android", "receive.yaml", "reports/android/ios-to-android"), listOf("ios", "send.yaml", "reports/ios/ios-to-android"),
            listOf("ios", "receive.yaml", "reports/ios/android-to-ios"), listOf("android", "send.yaml", "reports/android/android-to-ios"),
        ), exchange.runs.map { listOf(it[0], it[1], it[3]) })
        val tokens = exchange.runs.map { it[2] }
        assertEquals(tokens[0], tokens[1])
        assertEquals(tokens[2], tokens[3])
        assertTrue(tokens[0].startsWith("ios to android ") && tokens[2].startsWith("android to ios ") && tokens[0].substringAfterLast(' ') != tokens[2].substringAfterLast(' '))
        assertTrue(tokens.none { ',' in it })
        assertEquals(listOf(
            "beforeSuite android receive.yaml,send.yaml true", "beforeSuite ios receive.yaml,send.yaml true",
            "beforeFlow android receive.yaml true", "beforeFlow ios send.yaml true", "afterFlow ios send.yaml true", "afterFlow android receive.yaml true",
            "beforeFlow ios receive.yaml true", "beforeFlow android send.yaml true", "afterFlow android send.yaml true", "afterFlow ios receive.yaml true",
            "afterSuite ios receive.yaml,send.yaml true", "afterSuite android receive.yaml,send.yaml true",
        ), exchange.hooks)
    }

    @Test fun `a failed send fails both phones and still closes every session`() {
        val exchange = exchange("send")
        assertEquals("Device exchange ios-to-android failed: the ios phone failed send.yaml", exchange.failure?.message)
        assertEquals(listOf("android receive.yaml", "ios send.yaml"), exchange.runs.map { "${it[0]} ${it[1]}" })
        assertEquals(listOf("afterFlow ios send.yaml false", "afterFlow android receive.yaml false",
            "afterSuite ios receive.yaml,send.yaml false", "afterSuite android receive.yaml,send.yaml false"), exchange.hooks.takeLast(4))
    }

    @Test fun `a receiver that stops before the send starts never sends`() {
        val exchange = exchange("receive")
        assertEquals("Device exchange ios-to-android failed: the android phone finished receive.yaml before send.yaml started", exchange.failure?.message)
        assertEquals(listOf("android receive.yaml"), exchange.runs.map { "${it[0]} ${it[1]}" })
        assertContains(exchange.hooks, "afterFlow ios send.yaml false")
    }
}
