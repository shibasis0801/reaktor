package dev.shibasis.reaktor.tooling

import dev.shibasis.dependeasy.model.*
import dev.shibasis.reaktor.tooling.delivery.BuildDeclarations
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BuildDeclarationsTest {
    @Test fun declarationsRetainTheirIdentityFactsAndSealedWrapperBinding() = fixture { root ->
        report(root, node("artifacts"), node("checks", Effect.Verify))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val build = workspace.prepare(TaskInvocation(TaskId("fixture:artifacts")),
            environment = mapOf("REAKTOR_ENVIRONMENT" to "fixture-local", "FIXTURE_SECRET" to "private-fixture"),
            sensitiveEnvironmentKeys = setOf("FIXTURE_SECRET"))
        assertEquals("fixture-local", root.resolve("build/planning-environment.txt").readText())
        assertEquals("fixture-local", build.request.environment["REAKTOR_ENVIRONMENT"])
        assertEquals("private-fixture", build.request.environment["FIXTURE_SECRET"])
        assertContains(build.request.sensitiveEnvironmentKeys, "FIXTURE_SECRET")
        val check = workspace.prepare(TaskInvocation(TaskId("fixture:checks")))
        assertEquals(root.resolve(wrapperName).canonicalPath, build.request.argv.first())
        assertEquals(GradleProtocol.RunTask, build.request.argv[1])
        assertEquals(SafetyClass.LocalArtifactWrite, build.plan.safety.classification)
        assertEquals(SafetyClass.ReadOnly, check.plan.safety.classification)
        val definition = assertNotNull(build.request.definitionSeal)
        assertTrue(definition.files.containsAll(check.request.definitionSeal!!.files.filter { "/contracts/" !in it.path && "/plans/" !in it.path }))
        assertTrue(root.resolve(BuildDeclarations.Report).canonicalFile in definition.files)
        val task = workspace.catalog.tasks.single { it.id == build.plan.taskId }
        assertEquals(TaskKind.Build, task.kind)
        assertEquals(node("artifacts"), BuildModelReader.decodeNode(task.attributes.getValue("buildNode")))
        assertEquals(TaskProvenance("dependeasy", "fixture/build.gradle.kts:1"), task.provenance)
    }

    @Test fun typedPrerequisitesCarryPhysicalHostAndWriteAuthorityWithoutTaskNameInference() = fixture { root ->
        val device = node("innocentName", Effect.Verify).copy(host = HostRequirement(execution = ExecutionHost.Local),
            kernel = KernelRef("fixture.Device", spec = KernelSpec.DeviceFlow("android", "fixture.app", "fixture:app", listOf("flow.yaml"),
                "contract.json", emptyList(), ToolRef("runner", "1"))))
        val parent = node("checks", Effect.Verify)
        val model = BuildModel(producer = Producer(version = "fixture"), builds = listOf(BuildIdentity("fixture", ".")), nodes = listOf(parent, device),
            edges = listOf(Edge.Includes(parent.id, device.id)))
        root.resolve(BuildDeclarations.Report).apply { parentFile.mkdirs(); writeText(BuildModelReader.encode(model)) }
        val declared = BuildDeclarations.read(root).single { it.node.id == parent.id }
        assertEquals(ExecutionHost.Local, declared.host.execution)
        assertEquals(SafetyClass.DeviceWrite, declared.safety)
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertEquals(SafetyClass.DeviceWrite, workspace.catalog.tasks.single { it.id.value == "fixture:checks" }.safety.classification)
        assertTrue(workspace.catalog.tasks.none { it.provenance.source == "gradle-settings" })
    }

    @Test fun changedDeclarationsAreRejectedBeforePlanningAndExecution() = fixture { root ->
        report(root, node("artifacts"))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val invocation = TaskInvocation(TaskId("fixture:artifacts"))
        val prepared = workspace.prepare(invocation)
        root.resolve("build.gradle.kts").appendText("\n")
        assertFailsWith<IllegalStateException> { workspace.prepare(invocation) }
        SupervisedProcessExecutor().use { executor ->
            assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) }
        }
        assertFailsWith<IllegalArgumentException> { BuildDeclarations.read(root) }
    }

    @Test fun compositeOwnershipAndEveryEffectRemainVisible() = fixture { root ->
        report(root, node("artifacts"), node("checks", Effect.Verify, "library"),
            node("serve", Effect.Develop), node("deploy", Effect.Deploy),
            node("external").copy(kind = NodeKind.External, invocation = Invocation.Command(listOf(listOf("tool", "run")))))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val targets = BuildDeclarations.read(root)
        assertEquals(5, targets.size)
        assertEquals(setOf(TaskKind.Build, TaskKind.Check, TaskKind.Develop, TaskKind.Deploy), targets.map { it.kind }.toSet())
        assertEquals(listOf(":library:checks"), BuildDeclarations.read(root).single { it.node.id.build == "library" }.tasks)
        assertNotNull(workspace.catalog.tasks.single { it.id.value == "fixture:external" }.unavailableReason)
        val deploy = workspace.prepare(TaskInvocation(TaskId("fixture:deploy")))
        assertEquals(SafetyClass.ProductionReversibleWrite, deploy.plan.safety.classification)
        assertEquals(5, workspace.catalog.tasks.count { "buildNode" in it.attributes })
    }

    @Test fun invalidAndStaleModelsFailClosedWhileHostRestrictionsStayExplicit() = fixture { root ->
        val platform = if (System.getProperty("os.name").lowercase().startsWith("mac")) HostPlatform.Linux else HostPlatform.Macos
        val file = report(root, node("native").copy(host = HostRequirement(platform)))
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertNotNull(workspace.catalog.tasks.single { it.id.value == "fixture:native" }.unavailableReason)
        assertFailsWith<IllegalStateException> { workspace.prepare(TaskInvocation(TaskId("fixture:native"))) }
        val valid = file.readText()
        file.writeText(valid.replace("\"schema\":2", "\"schema\":1"))
        assertFailsWith<IllegalArgumentException> { BuildDeclarations.read(root) }
        file.writeText("malformed")
        assertFails { BuildDeclarations.read(root) }
    }

    private fun node(name: String, effect: Effect = Effect.Build, build: String = "fixture") = Node(
        NodeId(build, ":", name), NodeKind.Target, name, effect, HostRequirement(),
        SourceSite(build, "build.gradle.kts", 1), KernelRef("fixture.Kernel"), Invocation.Gradle(listOf(":$name")),
        inputs = listOf(FileSet(listOf("src"))), outputs = listOf(FileSet(listOf("build/out"))),
        tools = listOf(ToolRef("fixture", "1")))

    private fun report(root: File, vararg nodes: Node) = root.resolve(BuildDeclarations.Report).apply {
        parentFile.mkdirs()
        writeText(BuildModelReader.encode(BuildModel(producer = Producer(version = "fixture"),
            builds = listOf(BuildIdentity("fixture", "."), BuildIdentity("library", "library")),
            declarations = listOf(DeclarationInput("fixture", "build.gradle.kts", BuildModelReader.digest(root.resolve("build.gradle.kts")))),
            nodes = nodes.toList())))
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("reaktor-build-declarations").toFile()
        try {
            root.resolve("package.json").writeText("""{"name":"fixture","reaktor":{}}""")
            root.resolve(wrapperName).apply {
                writeText("""
                    #!/usr/bin/env python3
                    import hashlib,json,os,sys
                    from pathlib import Path
                    root=Path(__file__).parent
                    request=Path(next(arg.split('=',1)[1] for arg in sys.argv if arg.startswith('-Pdependeasy.gradleRequest=')))
                    value=json.loads(request.read_text())
                    digest=hashlib.sha256(request.read_bytes()).hexdigest()
                    node=value['node'];task=':'+node['name']
                    if node['build']!='fixture':task=':'+node['build']+task
                    result={'schema':1,'node':node,'modelDigest':value['modelDigest'],'requestDigest':digest,
                      'contextDigest':'fixture-context','invocation':[task],'configuredMillis':3,
                      'tasks':[{'id':node,'dependencies':[]}]}
                    target=root/'build/dependeasy/plans'/(digest+'.json');target.parent.mkdir(parents=True,exist_ok=True)
                    target.write_text(json.dumps(result))
                    (root/'build/planning-environment.txt').write_text(os.environ.get('REAKTOR_ENVIRONMENT','absent'))
                """.trimIndent() + "\n")
                setExecutable(true)
            }
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\nincludeBuild(\"library\")\n")
            root.resolve("build.gradle.kts").writeText("\n")
            root.resolve("library").mkdir()
            root.resolve("library/settings.gradle.kts").writeText("rootProject.name = \"library\"\n")
            root.resolve("library/build.gradle.kts").writeText("\n")
            block(root)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
    private val wrapperName = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
}
