package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BackendPipelineTest {
    @TempDir lateinit var directory: File

    private fun run(vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--stacktrace").build()


    private fun prepareLock(pattern: String) {
        val root = directory.resolve("package.json")
        if (!root.exists()) root.writeText("""{"name":"fixture-root","private":true}""")
        directory.resolve("pnpm-workspace.yaml").writeText("packages:\n  - '$pattern'\n")
        run("pnpmLock")
    }

    @Test fun `incomplete registry packages recover from the frozen lock with configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"repairFixture\"")
        directory.resolve("packages/tool/package.json").apply {
            parentFile.mkdirs()
            writeText("""{"name":"fixture-tool","private":true,"dependencies":{"is-number":"7.0.0"}}""")
        }
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy { javascript("tool", "packages/tool") }
        """.trimIndent())
        prepareLock("packages/*")
        val lock = directory.resolve("pnpm-lock.yaml").readText()
        assertEquals(TaskOutcome.SUCCESS, run("pnpmInstall").task(":pnpmInstall")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("pnpmInstall").task(":pnpmInstall")?.outcome)
        val packageMarker = directory.resolve("packages/tool/node_modules/is-number/package.json")
        assertTrue(packageMarker.delete())
        val repaired = run("pnpmInstall")
        assertContains(repaired.output, "Reusing configuration cache")
        assertContains(repaired.output, "Repairing incomplete pnpm registry packages")
        assertEquals(TaskOutcome.SUCCESS, repaired.task(":pnpmInstall")?.outcome)
        assertTrue(packageMarker.isFile)
        assertEquals(lock, directory.resolve("pnpm-lock.yaml").readText())
        assertEquals(TaskOutcome.UP_TO_DATE, run("pnpmInstall").task(":pnpmInstall")?.outcome)
    }

    @Test fun `frozen installs never rewrite locks and explicit lock updates reuse configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"lockFixture\"")
        val first = directory.resolve("packages/first/package.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"fixture-first","private":true}""")
        }
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy { javascript("first", "packages/first") }
        """.trimIndent())
        prepareLock("packages/*")
        val lock = directory.resolve("pnpm-lock.yaml")
        val original = lock.readText()
        first.writeText("""{"name":"fixture-first","private":true,"dependencies":{"fixture-second":"workspace:*"}}""")
        directory.resolve("packages/second/package.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"fixture-second","private":true}""")
        }
        val failure = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("pnpmInstall", "--configuration-cache").buildAndFail()
        assertContains(failure.output, "OUTDATED_LOCKFILE")
        assertEquals(original, lock.readText())
        assertEquals(TaskOutcome.SUCCESS, run("pnpmLock").task(":pnpmLock")?.outcome)
        assertNotEquals(original, lock.readText())
        assertContains(run("pnpmLock").output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, run("pnpmInstall").task(":pnpmInstall")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("pnpmInstall").task(":pnpmInstall")?.outcome)
    }

    @Test fun `pnpm tracks declared workspaces outside product directory conventions`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"workspaceFixture\"")
        directory.resolve("packages/tool").mkdirs()
        directory.resolve("package.json").writeText("""
            {"name":"fixture","private":true,"workspaces":["packages/*"]}
        """.trimIndent())
        val manifest = directory.resolve("packages/tool/package.json")
        manifest.writeText("""{"name":"fixture-tool","version":"1.0.0"}""")
        directory.resolve("package-lock.json").writeText("""
            {"name":"fixture","lockfileVersion":3,"requires":true,"packages":{
                "":{"name":"fixture","workspaces":["packages/*"]},
                "node_modules/fixture-tool":{"resolved":"packages/tool","link":true},
                "packages/tool":{"name":"fixture-tool","version":"1.0.0"}}}
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val tool = javascript("tool", "packages/tool")
                dag("workspace") { target("installWorkspace", node(tool.install)) }
                workspace {
                    target("shipTool") { tasks(":installWorkspace"); worker = "fixture" }
                    packageScripts()
                }
            }
        """.trimIndent())
        prepareLock("packages/*")
        directory.resolve("pnpm-workspace.yaml").writeText("packages: ['packages/*', '!packages/ignored'] # authored membership\n")
        val ignored = directory.resolve("packages/ignored/package.json").apply {
            parentFile.mkdirs(); writeText("""{"name":"ignored-fixture","private":true}""")
        }
        val installed = run("installWorkspace", "generatePackageScripts")
        assertEquals(TaskOutcome.SUCCESS, installed.task(":generatePackageScripts")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, installed.task(":pnpmInstall")?.outcome)
        assertContains(directory.resolve("package.json").readText(), "./gradlew :shipTool")
        assertEquals(TaskOutcome.UP_TO_DATE, run("installWorkspace").task(":pnpmInstall")?.outcome)
        ignored.writeText("""{"name":"ignored-fixture","private":true,"description":"excluded change"}""")
        assertEquals(TaskOutcome.UP_TO_DATE, run("installWorkspace").task(":pnpmInstall")?.outcome)
        manifest.writeText("""{"name":"fixture-tool","version":"1.0.0","description":"changed"}""")
        assertEquals(TaskOutcome.SUCCESS, run("installWorkspace").task(":pnpmInstall")?.outcome)
    }

    @Test fun `cmake stays lazy and tracks headers`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"nativeFixture\"")
        directory.resolve("cpp").mkdir()
        directory.resolve("cpp/CMakeLists.txt").writeText("""
            cmake_minimum_required(VERSION 3.22)
            project(sample LANGUAGES CXX)
            add_library(sample STATIC sample.cpp)
        """.trimIndent())
        directory.resolve("cpp/value.h").writeText("constexpr int value = 1;\n")
        directory.resolve("cpp/sample.cpp").writeText("#include \"value.h\"\nextern \"C\" int sample() { return value; }\n")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val library = cmake("sample") { source = "cpp" }
                dag("native") { target("nativeBuild", node(library)) }
            }
        """.trimIndent())
        assertNull(run("help").task(":sampleCMake"))
        assertFalse(directory.resolve("build/dependeasy/native").exists())
        assertEquals(TaskOutcome.SUCCESS, run("nativeBuild").task(":sampleCMake")?.outcome)
        val repeat = run("nativeBuild")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":sampleCMake")?.outcome)
        assertContains(repeat.output, "Reusing configuration cache")
        directory.resolve("cpp/value.h").writeText("constexpr int value = 2;\n")
        assertEquals(TaskOutcome.SUCCESS, run("nativeBuild").task(":sampleCMake")?.outcome)
        assertTrue(directory.resolve("build/dependeasy/native/sample/Release/libsample.a").isFile)
    }

    @Test fun `pnpm tracks nested local manifests before installation`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"linkedFixture\"")
        directory.resolve("web").mkdir()
        directory.resolve("vendor/library/generated").mkdirs()
        directory.resolve("web/package.json").writeText("""
            {"name":"linked-fixture","version":"1.0.0","dependencies":{"local-library":"link:../vendor/library"}}
        """.trimIndent())
        directory.resolve("vendor/library/package.json").writeText("""
            {"name":"local-library","version":"1.0.0","dependencies":{"generated-library":"link:./generated"}}
        """.trimIndent())
        val generated = directory.resolve("vendor/library/generated/package.json")
        generated.writeText("""{"name":"generated-library","version":"1.0.0"}""")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val web = javascript("linked", "web")
                dag("linked") { target("installLinked", node(web.install)) }
            }
        """.trimIndent())
        prepareLock("web")
        assertEquals(TaskOutcome.SUCCESS, run("installLinked").task(":pnpmInstall")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("installLinked").task(":pnpmInstall")?.outcome)
        generated.writeText("""{"name":"generated-library","version":"1.0.0","description":"changed"}""")
        assertEquals(TaskOutcome.SUCCESS, run("installLinked").task(":pnpmInstall")?.outcome)
    }

    @Test fun `swift package uses incremental locked builds`() {
        val compilerAvailable = runCatching {
            ProcessBuilder("swift", "--version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(compilerAvailable, "This integration fixture requires a Swift compiler")
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"swiftFixture\"")
        directory.resolve("swift/Sources/Sample").mkdirs()
        directory.resolve("swift/Package.swift").writeText("""
            // swift-tools-version: 6.0
            import PackageDescription
            let package = Package(name: "Sample", products: [.library(name: "Sample", targets: ["Sample"])],
                targets: [.target(name: "Sample")], swiftLanguageModes: [.v6])
        """.trimIndent())
        val source = directory.resolve("swift/Sources/Sample/Sample.swift")
        source.writeText("public func value() -> Int { 1 }\n")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val packageBuild = swiftPackage("sample", "swift").build()
                dag("swift") { target("swiftBuild", node(packageBuild)) }
            }
        """.trimIndent())
        assertNull(run("help").task(":sampleSwiftBuild"))
        assertEquals(TaskOutcome.SUCCESS, run("swiftBuild").task(":sampleSwiftBuild")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("swiftBuild").task(":sampleSwiftBuild")?.outcome)
        source.writeText("public func value() -> Int { 2 }\n")
        assertEquals(TaskOutcome.SUCCESS, run("swiftBuild").task(":sampleSwiftBuild")?.outcome)
    }
    @Test fun `generated web assets feed the bundle without becoming authored typecheck inputs`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"webFixture\"")
        directory.resolve("web").mkdir()
        directory.resolve("web/package.json").writeText("""
            {"name":"fixture-web","version":"1.0.0","scripts":{
                "generate:assets":"node generate.cjs","typecheck":"node types.cjs","bundle:assets":"node bundle.cjs"}}
        """.trimIndent())
        directory.resolve("web/bundle.cjs").writeText("""
            const fs = require('node:fs');
            fs.mkdirSync('dist', {recursive: true});
            fs.copyFileSync('public/generated/asset.txt', 'dist/result.txt');
        """.trimIndent())
        directory.resolve("web/generate.cjs").writeText("""
            const fs = require('node:fs');
            fs.mkdirSync('public/generated', {recursive: true});
            fs.copyFileSync('source.txt', 'public/generated/asset.txt');
        """.trimIndent())
        directory.resolve("web/types.cjs").writeText("""
            require('node:fs').readFileSync('source.txt', 'utf8');
        """.trimIndent())
        val source = directory.resolve("web/source.txt")
        source.writeText("first")
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val web = javascript("sample", "web")
                web.build("webBuild", bundle = web.script("bundle:assets", "dist"),
                    typecheck = web.command("types", null, "run", "typecheck"),
                    generators = listOf(web.script("generate:assets", "public/generated")))
            }
        """.trimIndent())
        assertNull(run("help").task(":pnpmInstall"))
        prepareLock("web")
        val initial = run("webBuild")
        assertEquals(TaskOutcome.SUCCESS, initial.task(":sampleGenerateAssets")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, initial.task(":sampleTypes")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, initial.task(":sampleBundleAssets")?.outcome)
        val repeat = run("webBuild")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":sampleGenerateAssets")?.outcome, repeat.output)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":sampleTypes")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeat.task(":sampleBundleAssets")?.outcome)
        source.writeText("second")
        val changed = run("webBuild")
        assertEquals(TaskOutcome.UP_TO_DATE, changed.task(":pnpmInstall")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":sampleGenerateAssets")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":sampleTypes")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":sampleBundleAssets")?.outcome)
        assertEquals("second", directory.resolve("web/dist/result.txt").readText())
        assertTrue(directory.resolve("web/public/generated/asset.txt").delete())
        val recovered = run("webBuild")
        assertEquals(TaskOutcome.SUCCESS, recovered.task(":sampleGenerateAssets")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, recovered.task(":sampleTypes")?.outcome)
        assertEquals("second", directory.resolve("web/dist/result.txt").readText())
    }
    @Test fun `Kotlin exports follow install without creating an installation cycle`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"producerFixture\"\ninclude(\"library\")")
        directory.resolve("package.json").writeText("""{"name":"producer-fixture","private":true}""")
        val source = directory.resolve("library/src/producer.js").apply {
            parentFile.mkdirs(); writeText("export const value = 42;\n")
        }
        directory.resolve("library/build.gradle.kts").writeText("""
            tasks.register<Sync>("exportKotlinLibrary") {
                dependsOn(":pnpmInstall")
                from("src/producer.js")
                into(layout.buildDirectory.dir("producer"))
            }
        """.trimIndent())
        directory.resolve("verify.mjs").writeText("""
            import { readFileSync } from 'node:fs';
            if (!readFileSync('library/build/producer/producer.js', 'utf8').includes('export const value')) throw new Error('Missing producer');
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                val consumer = javascript("consumer") {
                    kotlinLibraries("library")
                    sources("verify.mjs", "library/build/producer/producer.js")
                }
                dag("exports") { target("verifyExports", node(consumer.command("verify", null, "exec", "node", "verify.mjs"))) }
            }
        """.trimIndent())
        prepareLock("library")
        val first = run("verifyExports")
        assertEquals(TaskOutcome.SUCCESS, first.task(":library:exportKotlinLibrary")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":consumerVerify")?.outcome)
        val repeated = run("verifyExports")
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":consumerVerify")?.outcome)
        source.writeText("export const value = 43;\n")
        assertEquals(TaskOutcome.SUCCESS, run("verifyExports").task(":consumerVerify")?.outcome)
    }

}
