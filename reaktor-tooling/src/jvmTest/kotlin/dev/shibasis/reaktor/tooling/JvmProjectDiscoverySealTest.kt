package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmProjectDiscoverySealTest {
    @Test
    fun gradleTasksShareOneCompleteDiscoverySealWhileProvenanceStaysTaskSpecific() = fixture { root, included ->
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val declared = workspace.prepare(TaskInvocation(TaskId("target/app/build")))
        val module = workspace.prepare(TaskInvocation(TaskId("gradle/module0/build")))
        val first = assertNotNull(declared.request.definitionSeal)
        val second = assertNotNull(module.request.definitionSeal)

        assertSame(first.files, second.files, "One builder must reuse its captured Gradle definition")
        assertSame(first.directories, second.directories)
        assertEquals(declared.request.argv, module.request.argv)
        assertNotEquals(declared.plan.fingerprint, module.plan.fingerprint,
            "Distinct provenance paths must still bind distinct plans")
        assertEquals("package.json#reaktor.targets.app.build",
            workspace.catalog.tasks.first { it.id == declared.plan.taskId }.provenance.path)
        assertEquals("settings.gradle.kts", workspace.catalog.tasks.first { it.id == module.plan.taskId }.provenance.path)

        val expected = ProcessDefinitionSeal.capture(
            files = listOf(File(root, wrapperName), File(root, "settings.gradle.kts"),
                File(root, "build.gradle.kts"), File(root, "gradle.properties")),
            directories = listOf(ProcessDefinitionDirectory(root, suffixes),
                ProcessDefinitionDirectory(File(root, "buildSrc")), ProcessDefinitionDirectory(included, suffixes)),
        )
        assertEquals(expected, first, "Reuse must preserve the exact pre-existing definition closure")
    }

    @Test
    fun laterDiscoveryAndBothExecutionGatesRecaptureChangedDefinitions() = fixture { root, included ->
        val discovery = JvmProjectDiscovery()
        val first = assertNotNull(discovery.discoverWorkspace(root))
        val invocation = TaskInvocation(TaskId("gradle/module0/build"))
        val prepared = first.prepare(invocation)
        val oldSeal = assertNotNull(prepared.request.definitionSeal)
        File(included, "added.gradle.kts").writeText("// New included-build member\n")

        assertFailsWith<IllegalStateException> { first.prepare(invocation) }
        SupervisedProcessExecutor().use { executor ->
            assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) }
        }

        val second = assertNotNull(discovery.discoverWorkspace(root))
        val refreshed = assertNotNull(second.prepare(invocation).request.definitionSeal)
        assertNotSame(oldSeal.files, refreshed.files, "The same discovery object must create a fresh builder per pass")
        assertNotEquals(oldSeal.digest, refreshed.digest)
        File(root, "buildSrc/Rules.kt").appendText("// Changed build logic\n")
        assertFailsWith<IllegalStateException> { second.prepare(invocation) }
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
        assertEquals(SafetyClass.NonProductionWrite, prepared.plan.safety.classification)
        assertTrue(prepared.request.definitionSeal!!.files.any { it.name == "check.mjs" })
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
        assertEquals(SafetyClass.ProductionReversibleWrite, prepared.plan.safety.classification)
        assertTrue(prepared.request.definitionSeal!!.files.any { it == File(root, "targets/worker/package.json").canonicalFile })
        assertTrue(prepared.request.definitionSeal!!.directories.any { it.directory == File(root, "targets/worker").canonicalFile })
        File(root, "targets/worker/src/index.ts").appendText("// Changed deploy source")
        assertFailsWith<IllegalStateException> { workspace.prepare(TaskInvocation(TaskId("npm/deploy"))) }
        assertEquals("worker", PackageScriptCommands.exactWorkspaceDeploy("pnpm --filter worker run deploy"))
        assertNull(PackageScriptCommands.exactWorkspaceDeploy("pnpm --filter worker run deploy && node other.mjs"))
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

    @Test
    fun generatedGradleWorkerAliasSealsBuildAndWorkerDefinitions() = fixture { root, included ->
        worker(root)
        File(root, "targets/worker/src/index.ts").writeText("export default {};")
        val manifest = generatedWorkerManifest()
        File(root, "package.json").writeText(manifest)
        val helper = File(included, "dependeasy/javascript/cli/worker-deploy.ts").apply {
            parentFile.mkdirs(); writeText("// Shared deployment implementation")
        }
        val binding = assertNotNull(dev.shibasis.reaktor.tooling.delivery.GradlePackageTargets.workerDeploy(
            root, kotlinx.serialization.json.Json.parseToJsonElement(manifest).jsonObject, "deployWorker", "./gradlew :deployWorker"))
        assertEquals(setOf("dev", "prod"), binding.environments)
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val invocation = TaskInvocation(TaskId("npm/deployWorker"))
        val prepared = workspace.prepare(invocation)
        assertEquals(SafetyClass.ProductionReversibleWrite, prepared.plan.safety.classification)
        val seal = assertNotNull(prepared.request.definitionSeal)
        assertTrue(seal.files.any { it == File(root, "targets/worker/package.json").canonicalFile })
        assertTrue(seal.directories.any { it.directory == included.canonicalFile })
        assertTrue(seal.directories.any { it.directory == File(included, "dependeasy").canonicalFile })
        helper.appendText("\n// Changed implementation")
        assertFailsWith<IllegalStateException> { workspace.prepare(invocation) }
        SupervisedProcessExecutor().use { executor -> assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) } }
    }

    @Test
    fun generatedGradleWorkerAliasRejectsEscapingPathsAndCommandDrift() = fixture { root, _ ->
        File(root, "targets/worker").mkdirs()
        fun binding(directory: String = "targets/worker", command: String = "./gradlew :deployWorker") =
            dev.shibasis.reaktor.tooling.delivery.GradlePackageTargets.workerDeploy(root,
                kotlinx.serialization.json.Json.parseToJsonElement(generatedWorkerManifest(directory)).jsonObject, "deployWorker", command)
        assertNotNull(binding())
        assertNull(binding("../included"))
        assertNull(binding(command = "./gradlew :deployWorker && node other.mjs"))
        assertNull(binding(command = "./gradlew :different"))
    }

    private fun generatedWorkerManifest(directory: String = "targets/worker") = """{
        "name":"gradle-worker","reaktor":{},"workspaces":["targets/worker"],
        "scripts":{"deployWorker":"./gradlew :deployWorker"},
        "dependeasy":{"generatedScripts":["deployWorker"],"generatedTargets":{"deployWorker":{
            "task":":deployWorker","effect":"deploy","worker":"fixture","workerDirectory":"$directory","environments":["dev","prod"]
        }}}
    }"""

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
            File(root, "package.json").writeText("""{"name":"seal-fixture","reaktor":{"targets":{"app":{"gradle":":module0","build":"build"}}}}""")
            File(root, wrapperName).writeText("fixture wrapper; tests never execute this file\n")
            File(root, "settings.gradle.kts").writeText(
                "includeBuild(\"../included\")\n" + (0 until modules).joinToString("\n") { "include(\":module$it\")" },
            )
            File(root, "build.gradle.kts").writeText("// Fixture build\n")
            File(root, "gradle.properties").writeText("fixture=true\n")
            File(root, "buildSrc/Rules.kt").apply { parentFile.mkdirs(); writeText("// Fixture build logic\n") }
            File(included, "build.gradle.kts").writeText("// Fixture included build\n")
            block(root, included)
        } finally { parent.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    private val wrapperName = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
    private val suffixes = setOf(".gradle", ".gradle.kts", ".toml", ".properties")
}
