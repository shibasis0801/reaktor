package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class VerificationSuitesTest {
    @TempDir lateinit var root: File
    private fun write(path: String, text: String) = root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    @Test fun `suite kernels execute pinned argv and exclude environment values from the model`() {
        write("settings.gradle.kts", "rootProject.name = \"verificationFixture\"")
        write("suite/input.feature", "Feature: fixture")
        write("suite/input.js", "export default function() {}")
        write("config/karate-config.js", "function fn() { return {}; }")
        write("environment.env", "PRIVATE_FIXTURE=never-export-this-marker\n")
        write("jar", "fixture")
        write("tool", "#!/bin/sh\nprintf '%s\n' \"\$@\" >> arguments\nprintf '%s' \"\$PRIVATE_FIXTURE\" > private-proof\n").setExecutable(true)
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.verification.*
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val karate = tasks.register<KarateSuite>("karate") {
                workingDirectory.set(layout.projectDirectory); executable.set(file("tool").absolutePath); toolVersion.set("2.0.0")
                jar.set(layout.projectDirectory.file("jar")); suites.set(listOf("suite/input.feature")); tags.set(listOf("@smoke"))
                arguments.set(listOf("--configdir", "config")); sourceFiles.from(file("suite/input.feature")); environmentFiles.from(file("environment.env"))
                outputDirectories.from(layout.buildDirectory.dir("reports/karate"))
            }
            val k6 = tasks.register<K6Suite>("k6") {
                workingDirectory.set(layout.projectDirectory); executable.set(file("tool").absolutePath); toolVersion.set("2.1.0")
                script.set(layout.projectDirectory.file("suite/input.js")); environmentFiles.from(file("environment.env"))
                sourceFiles.from(file("suite/input.js")); outputFiles.from(layout.buildDirectory.file("reports/k6/summary.json"))
            }
            dependeasy.workspace { verify("suites") { includes(node(karate), node(k6)) } }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments("suites", "exportBuildModel", "--configuration-cache").build()
        run()
        assertEquals("never-export-this-marker", root.resolve("private-proof").readText())
        val model = BuildModelReader.read(root)
        assertTrue(model.nodes.any { it.kernel?.spec is KernelSpec.KarateSuite })
        assertTrue(model.nodes.any { it.kernel?.spec is KernelSpec.K6Suite })
        assertFalse(root.resolve(BuildModelReader.RelativePath).readText().contains("never-export-this-marker"))
        val argv = root.resolve("arguments").readText()
        assertContains(argv, "--summary-export")
        assertContains(argv, "@smoke")
        assertContains(run().output, "Reusing configuration cache")
    }
    @Test fun `signature replacement requires opt in and local host propagates to its target`() {
        write("settings.gradle.kts", "rootProject.name = \"physicalFixture\"")
        write("flow.yaml", "appId: fixture.app\n---\n- launchApp\n")
        write("app.apk", "fixture")
        write("runner", """
            #!/usr/bin/env python3
            import sys,json
            from pathlib import Path
            if 'devices' in sys.argv:
                print(json.dumps([dict(platform='android',kind='device',id='fixture-phone',ready=True)]))
            else:
                Path('flow-ran').write_text('passed')
        """.trimIndent()).setExecutable(true)
        write("adb", """
            #!/usr/bin/env python3
            import sys
            from pathlib import Path
            if 'uninstall' in sys.argv:
                Path('uninstalled').write_text('explicit')
            elif not Path('uninstalled').exists():
                print('INSTALL_FAILED_UPDATE_INCOMPATIBLE')
                sys.exit(1)
        """.trimIndent()).setExecutable(true)
        write("build.gradle.kts", """
            import dev.shibasis.dependeasy.verification.DeviceFlow
            import dev.shibasis.dependeasy.handles.*
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            val flow = tasks.register<DeviceFlow>("flow") {
                workingDirectory.set(layout.projectDirectory); platform.set("android"); applicationId.set("fixture.app")
                appOutput.set("physicalFixture:app:outputAndroidApkDebug"); appFile.set(layout.projectDirectory.file("app.apk"))
                runner.set(layout.projectDirectory.file("runner")); runnerVersion.set("1.1.27"); adbExecutable.set(layout.projectDirectory.file("adb"))
                flowFiles.from(file("flow.yaml")); flowPaths.set(listOf(file("flow.yaml").absolutePath))
                reportDirectory.set(layout.buildDirectory.dir("reports/flow")); contractFile.set(layout.buildDirectory.file("dependeasy/device/flow.json"))
                installApplication.set(true)
                uninstallOnSignatureMismatch.set(providers.gradleProperty("allowUninstall").map(String::toBooleanStrict).orElse(false))
            }
            dependeasy.workspace { verify("phoneCheck") { includes(node(flow, OutputKind.VerificationResults)) } }
        """.trimIndent())
        fun runner(vararg args: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")
        runner("exportBuildModel").build()
        val hosts = BuildModelReader.json.decodeFromString<RemoteHostsContract>(root.resolve("build/dependeasy/remote/hosts.json").readText())
        assertEquals(ExecutionHost.Local, hosts.taskHosts.getValue(":phoneCheck").execution)
        val denied = runner("phoneCheck").buildAndFail()
        assertContains(denied.output, "signature replacement requires")
        assertFalse(root.resolve("uninstalled").exists())
        assertFalse(root.resolve("flow-ran").exists())
        runner("phoneCheck", "-PallowUninstall=true").build()
        assertEquals("explicit", root.resolve("uninstalled").readText())
        assertEquals("passed", root.resolve("flow-ran").readText())
    }

    @Test fun `pinned Karate and k6 execute real HTTP suites`() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fixture") { exchange ->
            requests.incrementAndGet()
            val body = """{"value":"passed"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/fixture"
            write("settings.gradle.kts", "rootProject.name = \"pinnedVerificationFixture\"")
            write("suite/input.feature", """
                Feature: local service qualification
                Scenario: fetch declared service
                  Given url '$url'
                  When method get
                  Then status 200
                  And match response.value == 'passed'
            """.trimIndent())
            write("suite/input.js", """
                import http from 'k6/http';
                import { check } from 'k6';
                export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
                export default function() {
                  const response = http.get('$url');
                  check(response, { 'declared service': value => value.status === 200 && value.json('value') === 'passed' });
                }
            """.trimIndent())
            write("config/karate-config.js", "function fn() { return {}; }")
            write("build.gradle.kts", """
                plugins { id("dev.shibasis.dependeasy.pipeline") }
                dependeasy.workspace {
                    val karate = karateSuite("realKarate", "suite/input.feature", "config", emptyList(), emptyList())
                    val k6 = k6Suite("realK6", "suite/input.js", emptyList())
                    verify("qualification") { includes(karate, k6) }
                }
            """.trimIndent())
            GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withArguments("qualification", "--configuration-cache", "--stacktrace").build()
            assertEquals(2, requests.get())
            assertTrue(root.resolve("build/reports/karate/realKarate").walkTopDown().any { it.isFile })
            val summary = BuildModelReader.json.parseToJsonElement(root.resolve("build/reports/k6/realK6.json").readText())
            assertContains(summary.toString(), "checks")
        } finally { server.stop(0) }
    }

}
