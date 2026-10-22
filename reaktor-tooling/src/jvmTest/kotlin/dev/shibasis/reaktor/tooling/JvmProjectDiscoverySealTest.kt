package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.dependeasy.model.*

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmProjectDiscoverySealTest {
    @Test
    fun legacyPackageGradleReferencesCannotBypassDeclaredBuildNodes() = fixture { root, _ ->
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertTrue(workspace.catalog.tasks.none { it.provider == "gradle" })
        assertFailsWith<IllegalArgumentException> {
            workspace.prepare(TaskInvocation(TaskId("target/app/build")))
        }
    }

    @Test
    fun generatedWorkerSealsOnlyItsManifestClosureAndRejectsChangedImports() = fixture { root, _ ->
        worker(root)
        val bundle = File(root, "modules/app/bestbuds-kt").apply { mkdirs() }.canonicalFile
        repeat(30_000) { File(bundle, "unused-$it.mjs").writeText("export const unused = $it;") }
        val entry = File(bundle, "worker.mjs").apply { writeText("export { value } from './dependency.mjs';") }
        val dependency = File(bundle, "dependency.mjs").apply { writeText("export const value = 1;") }
        bundleManifest(root, listOf(entry, dependency))
        DefinitionDigestCache.invalidate()
        val start = System.nanoTime()
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMillis < 3_000, "30,000-file generated bundle discovery took $elapsedMillis ms")
        val invocation = TaskInvocation(TaskId("target/worker/npm/deploy"))
        assertNull(workspace.catalog.tasks.first { it.id == invocation.taskId }.unavailableReason)
        val prepared = workspace.prepare(invocation)
        val seal = assertNotNull(prepared.request.definitionSeal)
        assertEquals(setOf("worker.mjs", "dependency.mjs", "package.json"), seal.files.filter { it.parentFile == bundle }.map { it.name }.toSet())
        assertTrue(seal.directories.none { it.directory == bundle })
        File(bundle, "unused-1.mjs").writeText("export const unrelated = 10;")
        assertEquals(seal.digest, workspace.prepare(invocation).request.definitionSeal?.digest)
        dependency.writeText("export const value = 2;")
        assertFailsWith<IllegalStateException> { workspace.prepare(invocation) }
        SupervisedProcessExecutor().use { executor -> assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) } }
        val changed = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertTrue(assertFailsWith<IllegalArgumentException> { changed.prepare(invocation) }.message.orEmpty().contains("manifest is stale"))
        println("worker-manifest discovery-ms=$elapsedMillis files=${seal.files.size}")
    }

    @Test
    fun generatedWorkerMissingIncompleteAndEscapingManifestsFailClosed() = fixture { root, _ ->
        worker(root)
        val entry = File(root, "modules/app/bestbuds-kt/worker.mjs").apply { parentFile.mkdirs(); writeText("export const value = 1;") }
        fun reason(): String = runCatching {
            assertNotNull(JvmProjectDiscovery().discoverWorkspace(root)).prepare(TaskInvocation(TaskId("target/worker/npm/deploy")))
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(reason().contains("manifest is unavailable"))
        bundleManifest(root, listOf(entry), imported = "bestbuds-kt/other.mjs")
        assertTrue(reason().contains("manifest has no bestbuds-kt/worker.mjs"))
        bundleManifest(root, listOf(entry), path = "../../../package.json")
        assertTrue(reason().contains("path escapes"))
        bundleManifest(root, listOf(entry), path = "missing.mjs")
        assertTrue(reason().contains("member is missing"))
        bundleManifest(root, listOf(entry))
        assertEquals("", reason())
        entry.writeText("export { value } from './unlisted.mjs';")
        File(entry.parentFile, "unlisted.mjs").writeText("export const value = 1;")
        bundleManifest(root, listOf(entry))
        assertTrue(reason().contains("manifest omits a dependency"))
    }

    @Test
    fun pnpmAliasesRetainRemoteEffectsAndUseTheManagedRuntime() = fixture { root, _ ->
        File(root, "package.json").writeText("""{"name":"pnpm-fixture","packageManager":"pnpm@12.10.1","reaktor":{},"scripts":{"check":"pnpm run nested","nested":"TARGET_ENV=dev node tools/check.mjs karate tests/notificationServer"}}""")
        File(root, "tools/check.mjs").apply { parentFile.mkdirs(); writeText("export const value = 1;") }
        File(root, "tests/notificationServer/flow.feature").apply { parentFile.mkdirs(); writeText("Feature: fixture") }
        val executable = File(root, "build/dependeasy/tools/javascript/bin/pnpm").apply {
            parentFile.mkdirs(); writeText("fixture; never executed"); setExecutable(true)
        }
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val prepared = workspace.prepare(TaskInvocation(TaskId("npm/check"), arguments = listOf("--verbose")))
        assertEquals(listOf(executable.canonicalPath, "run", "check", "--verbose"), prepared.request.argv)
        assertTrue(prepared.request.environment.getValue("PATH").startsWith(executable.canonicalFile.parent))
        assertEquals(SafetyClass.UnknownRemoteEffect, prepared.plan.safety.classification)
        assertTrue(prepared.request.definitionSeal!!.files.any { it.name == "check.mjs" })
        assertEquals(TaskKind.Custom, workspace.catalog.tasks.single { it.id.value == "npm/check" }.kind)
        val names = listOf("status", "build", "deploy", "delete", "testflight", "migrate")
        val scripts = names.joinToString(",") { name -> "\"$name\":\"node --version\"" }
        File(root, "package.json").writeText("""{"name":"opaque","reaktor":{},"scripts":{$scripts}}""")
        val opaqueNames = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        for (name in names) {
            val task = opaqueNames.catalog.tasks.single { it.id.value == "npm/$name" }
            assertEquals(TaskKind.Custom, task.kind)
            assertEquals(SafetyClass.UnknownRemoteEffect, task.safety.classification)
            assertEquals(SafetyClass.UnknownRemoteEffect,
                opaqueNames.prepare(TaskInvocation(task.id, arguments = listOf("--environment", "prod", "--delete", "fixture"))).plan.safety.classification)
        }
        File(root, "package.json").writeText("""{"name":"opaque-gradle","reaktor":{},"scripts":{"check":"./gradlew :workers:karateAuthProd","build":"./gradlew :workers:build"}}""")
        val opaque = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        for (name in listOf("check", "build")) {
            val task = opaque.catalog.tasks.single { it.id.value == "npm/$name" }
            assertEquals(SafetyClass.UnknownRemoteEffect, task.safety.classification)
            assertTrue(task.unavailableReason.orEmpty().contains("canonical build node"))
            assertFailsWith<IllegalStateException> { opaque.prepare(TaskInvocation(task.id)) }
        }
    }

    @Test
    fun pnpmWorkspaceDeploySealsItsTargetAndRejectsLaterChanges() = fixture { root, _ ->
        worker(root)
        val rootManifest = File(root, "package.json")
        rootManifest.writeText("""{"name":"pnpm-workspace","packageManager":"pnpm@12.10.1","reaktor":{},"workspaces":["targets/worker"],"scripts":{"deploy":"pnpm --filter worker run deploy"}}""")
        // This fixture needs only authored source, so it has no generated worker imports.
        File(root, "targets/worker/src/index.ts").writeText("export default {};")
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val prepared = workspace.prepare(TaskInvocation(TaskId("npm/deploy")))
        assertEquals(SafetyClass.UnknownRemoteEffect, prepared.plan.safety.classification)
        assertTrue(prepared.request.definitionSeal!!.files.any { it == File(root, "targets/worker/package.json").canonicalFile })
        assertTrue(prepared.request.definitionSeal!!.directories.any { it.directory == File(root, "targets/worker").canonicalFile })
        File(root, "targets/worker/src/index.ts").appendText("// Changed deploy source")
        assertFailsWith<IllegalStateException> { workspace.prepare(TaskInvocation(TaskId("npm/deploy"))) }
    }

    @Test
    fun pnpmYamlMembershipBindsWorkspaceNamesAndSealsItsDefinition() = fixture { root, _ ->
        worker(root)
        File(root, "package.json").writeText("""{"name":"pnpm-workspace","packageManager":"pnpm@12.10.1","reaktor":{},"scripts":{"deploy":"pnpm --filter worker run deploy"}}""")
        val yaml = File(root, "pnpm-workspace.yaml").apply {
            writeText("packages: ['targets/*', '!targets/excluded'] # Authored workspace membership\n")
        }
        File(root, "targets/excluded/package.json").apply { parentFile.mkdirs(); writeText("""{"name":"excluded"}""") }
        File(root, "targets/worker/src/index.ts").writeText("export default {};")
        assertEquals(listOf("targets/worker"), PackageWorkspaces.paths(root))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertTrue(workspace.catalog.tasks.any { it.id.value == "target/worker/npm/deploy" })
        val prepared = workspace.prepare(TaskInvocation(TaskId("npm/deploy")))
        assertTrue(prepared.request.definitionSeal!!.files.any { it == yaml.canonicalFile })
        assertTrue(prepared.request.definitionSeal!!.files.any { it == File(root, "targets/worker/package.json").canonicalFile })
        yaml.writeText("packages: ['targets/*', 'packages/*']\n")
        assertFailsWith<IllegalStateException> { workspace.prepare(TaskInvocation(TaskId("npm/deploy"))) }
    }

    private fun worker(root: File) {
        File(root, "package.json").writeText("""{"name":"worker-seal","reaktor":{},"workspaces":["targets/worker"]}""")
        File(root, "targets/worker/package.json").apply { parentFile.mkdirs(); writeText("""{"name":"worker","scripts":{"deploy":"wrangler deploy"}}""") }
        File(root, "modules/app/bestbuds-kt/package.json").apply { parentFile.mkdirs(); writeText("""{"name":"bestbuds-kt","main":"worker.mjs"}""") }
        File(root, "targets/worker/src/index.ts").apply { parentFile.mkdirs(); writeText("import { value } from 'bestbuds-kt/worker.mjs';") }
    }

    private fun bundleManifest(root: File, members: List<File>, imported: String = "bestbuds-kt/worker.mjs", path: String? = null) {
        val bundle = File(root, "modules/app/bestbuds-kt").canonicalFile
        val entries = (members + File(bundle, "package.json")).distinct().joinToString(",") { file -> """{"path":"${path ?: file.canonicalFile.relativeTo(bundle).invariantSeparatorsPath}","sha256":"${DefinitionDigestCache.digestOf(file)}"}""" }
        File(root, "modules/app/bestbuds-kt.manifest.json").writeText("""{"format":1,"imports":{"$imported":[$entries]}}""")
    }

    private fun fixture(modules: Int = 2, block: (File, File) -> Unit) {
        val parent = Files.createTempDirectory("reaktor-discovery-seal").toFile()
        try {
            val root = File(parent, "workspace").apply { mkdirs() }
            val included = File(parent, "included").apply { mkdirs() }
            File(root, "package.json").writeText("""{"name":"seal-fixture","reaktor":{"targets":{"app":{"gradle":":module0","build":"build"},"other":{"gradle":":module0","build":"build"}}}}""")
            File(root, wrapperName).writeText("fixture wrapper; tests never execute this file\n")
            File(root, "settings.gradle.kts").writeText(
                "includeBuild(\"../included\")\n" + (0 until modules).joinToString("\n") { "include(\":module$it\")" },
            )
            File(root, "build.gradle.kts").writeText("// Fixture build\n")
            File(root, "gradle.properties").writeText("fixture=true\n")
            File(root, "buildSrc/Rules.kt").apply { parentFile.mkdirs(); writeText("// Fixture build logic\n") }
            File(included, "build.gradle.kts").writeText("// Fixture included build\n")
            root.resolve(BuildModelReader.RelativePath).apply {
                parentFile.mkdirs()
                writeText(BuildModelReader.encode(BuildModel(producer = Producer(version = "fixture"),
                    builds = listOf(BuildIdentity("seal-fixture", "."), BuildIdentity("included", "../included")), nodes = emptyList())))
            }
            block(root, included)
        } finally { parent.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    private val wrapperName = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
}
