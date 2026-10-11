package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.delivery.GradleBuildTargets
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GradleBuildTargetsTest {
    @Test
    fun declaredTargetsUseTheExistingWrapperAndShareASealedDefinition() = fixture { root ->
        report(root, target("androidArtifacts"), target("appChecks", effect = "verify"))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val build = workspace.prepare(TaskInvocation(TaskId("dependeasy:androidArtifacts")))
        val check = workspace.prepare(TaskInvocation(TaskId("dependeasy:appChecks")))
        assertEquals(listOf(root.resolve(wrapperName).canonicalPath, ":androidArtifacts"), build.request.argv)
        assertEquals(SafetyClass.LocalArtifactWrite, build.plan.safety.classification)
        assertEquals(SafetyClass.ReadOnly, check.plan.safety.classification)
        val definition = assertNotNull(build.request.definitionSeal)
        assertSame(definition.files, check.request.definitionSeal?.files)
        assertSame(definition.directories, check.request.definitionSeal?.directories)
        assertTrue(root.resolve(GradleBuildTargets.Report).canonicalFile in definition.files)
        val task = workspace.catalog.tasks.first { it.id == build.plan.taskId }
        assertEquals(TaskKind.Build, task.kind)
        assertEquals(TaskProvenance("dependeasy", GradleBuildTargets.Report), task.provenance)
    }

    @Test
    fun declarationChangesAreRejectedBeforePlanningAndBeforeExecution() = fixture { root ->
        val file = report(root, target("androidArtifacts"))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val invocation = TaskInvocation(TaskId("dependeasy:androidArtifacts"))
        val prepared = workspace.prepare(invocation)
        file.appendText("\n ")
        assertFailsWith<IllegalStateException> { workspace.prepare(invocation) }
        SupervisedProcessExecutor().use { executor ->
            assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) }
        }
        val refreshed = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root)).prepare(invocation)
        assertNotEquals(prepared.request.definitionSeal?.digest, refreshed.request.definitionSeal?.digest)
        val unplanned = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        file.appendText("\n ")
        assertFailsWith<IllegalStateException> { unplanned.prepare(invocation) }
    }

    @Test
    fun compositeTargetsUseQualifiedWrapperPathsAndDeduplicateDiamondOwnership() = fixture { root ->
        val framework = document("framework", listOf(target("checks", owner = "framework", effect = "verify")))
        val product = document("product", listOf(target("artifacts", owner = "product")), listOf(framework))
        val unknown = document("unowned", listOf(target("artifacts", owner = "unowned")))
        val layout = layout(root)
        for (included in listOf(listOf(framework, product, unknown), listOf(product, framework, unknown))) {
            report(root).writeText(document("fixture", listOf(target("artifacts")), included))
            val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
            val declared = workspace.catalog.tasks.filter { it.id.value.startsWith("dependeasy:") }
            assertEquals(setOf("dependeasy:artifacts", "dependeasy:framework:checks", "dependeasy:product:artifacts"),
                declared.map { it.id.value }.toSet())
            assertEquals(3, declared.size)
            val prepared = workspace.prepare(TaskInvocation(TaskId("dependeasy:product:artifacts")))
            assertEquals(listOf(root.resolve(wrapperName).canonicalPath, ":product:artifacts"), prepared.request.argv)
            assertTrue(layout.canonicalFile in assertNotNull(prepared.request.definitionSeal).files)
        }
        val conflict = document("framework", listOf(target("checks", owner = "framework", effect = "build")))
        report(root).writeText(document("fixture", listOf(target("artifacts")), listOf(framework, conflict, product)))
        assertEquals(setOf(":artifacts", ":product:artifacts"), GradleBuildTargets.read(root).map { it.task }.toSet())
    }

    @Test
    fun compositeLayoutAndIncludedBuildDefinitionsRemainSealed() = fixture { root ->
        val shared = root.resolve("shared").apply { mkdirs() }
        val definition = shared.resolve("build.gradle.kts").apply { writeText("// Included definition\n") }
        root.resolve("settings.gradle.kts").appendText("includeBuild(\"shared\")\n")
        val layout = layout(root)
        val framework = document("framework", listOf(target("checks", owner = "framework", effect = "verify")))
        report(root).writeText(document("fixture", emptyList(), listOf(framework)))
        val invocation = TaskInvocation(TaskId("dependeasy:framework:checks"))
        val prepared = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root)).prepare(invocation)
        layout.appendText("\n ")
        SupervisedProcessExecutor().use { executor ->
            assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) }
        }
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        workspace.prepare(invocation)
        definition.appendText("// Changed definition\n")
        assertFailsWith<IllegalStateException> { workspace.prepare(invocation) }
    }

    @Test
    fun reportsCannotIntroduceCommandsRemoteAliasesOrNestedTaskPaths() = fixture { root ->
        report(root, target("androidArtifacts"), target("deployWorker", effect = "deploy"),
            target("external", kind = "external"), target("unsafe", command = "[\"sh\",\"-c\",\"echo unsafe\"]"),
            target("module:build"), target("wrongRunner", runner = "pnpm"),
            target("wrongPlatform", platform = "unknown"))
        assertEquals(listOf(":androidArtifacts"), GradleBuildTargets.read(root).map { it.task })
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertEquals(listOf("dependeasy:androidArtifacts"),
            workspace.catalog.tasks.filter { it.id.value.startsWith("dependeasy:") }.map { it.id.value })
        val file = root.resolve(GradleBuildTargets.Report)
        val valid = file.readText()
        for (unsupported in listOf(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("\"state\":\"declared\"", "\"state\":\"observed\""), "malformed")) {
            file.writeText(unsupported)
            assertTrue(GradleBuildTargets.read(root).isEmpty())
        }
    }

    @Test
    fun platformAndMoreDangerousEffectsRetainTheirExistingGates() = fixture { root ->
        val otherPlatform = if (System.getProperty("os.name").lowercase().startsWith("mac")) "linux" else "macos"
        report(root, target("nativeArtifacts", platform = otherPlatform), target("deployArtifacts"))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertNotNull(workspace.catalog.tasks.first { it.id.value == "dependeasy:nativeArtifacts" }.unavailableReason)
        assertFailsWith<IllegalStateException> {
            workspace.prepare(TaskInvocation(TaskId("dependeasy:nativeArtifacts")))
        }
        val remote = workspace.prepare(TaskInvocation(TaskId("dependeasy:deployArtifacts")))
        assertEquals(SafetyClass.ProductionReversibleWrite, remote.plan.safety.classification)
    }

    private fun target(name: String, effect: String = "build", kind: String = "gradle",
        runner: String = "gradle", platform: String = "portable", command: String = "[]", owner: String = "fixture") = """{
        "id":"$owner:$name","task":":$name","kind":"$kind","runner":"$runner",
        "platform":"$platform","effect":"$effect","command":$command,"commands":[],"recipes":[]
    }"""

    private fun document(owner: String, targets: List<String>, included: List<String> = emptyList()) = """
        {"schemaVersion":1,"kind":"workspace","state":"declared","id":"$owner",
         "targets":[${targets.joinToString(",")}],"includedBuilds":[${included.joinToString(",")}]}
    """.trimIndent()

    private fun layout(root: File) = root.resolve(GradleBuildTargets.Layout).apply {
        parentFile.mkdirs()
        writeText("""{"schemaVersion":1,"builds":{"fixture":".","framework":"shared","product":"product"}}""")
    }

    private fun report(root: File, vararg targets: String): File = root.resolve(GradleBuildTargets.Report).apply {
        parentFile.mkdirs()
        writeText("""{"schemaVersion":1,"kind":"workspace","state":"declared","id":"fixture","targets":[${targets.joinToString(",")}]}""")
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("reaktor-build-targets").toFile()
        try {
            root.resolve("package.json").writeText("""{"name":"fixture","reaktor":{}}""")
            root.resolve(wrapperName).writeText("fixture wrapper; never executed\n")
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
            root.resolve("build.gradle.kts").writeText("// Fixture build\n")
            block(root)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private val wrapperName = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
}
