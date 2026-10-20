package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class BuildModelReaderTest {
    @TempDir lateinit var root: File
    private val node = Node(NodeId("fixture", ":", "checks"), NodeKind.Target, "checks", Effect.Verify,
        HostRequirement(), SourceSite("fixture", "build.gradle.kts", 3), KernelRef("FixtureTask"), Invocation.Gradle(listOf(":checks")))
    private fun model() = BuildModel(producer = Producer(version = "fixture"), builds = listOf(BuildIdentity("fixture", ".")), nodes = listOf(node))

    @Test fun `relations exact task plans and recorded receipts preserve the same node identities`() {
        val app = node.copy(id = NodeId("fixture", ":app", "outputVerificationResultsJvm"),
            invocation = Invocation.Gradle(listOf(":app:jvmTest")), host = HostRequirement(execution = ExecutionHost.Local))
        val declared = model().copy(nodes = listOf(node, app), edges = listOf(Edge.Consumes(node.id, app.id, "VerificationResults:Jvm")))
        val file = root.resolve(BuildModelReader.RelativePath).apply { parentFile.mkdirs(); writeText(BuildModelReader.encode(declared)) }
        val details = BuildModelReader.nodeDetails(root, node.id.toString())
        assertEquals(app.id, BuildModelReader.json.decodeFromJsonElement<Edge>(details.getValue("edges").jsonArray.single()).to)
        assertEquals(ExecutionHost.Local, BuildModelReader.json.decodeFromJsonElement<HostRequirement>(details.getValue("effectiveHost")).execution)
        val page = BuildModelReader.modelPage(root, limit = 1)
        assertEquals(1, page.getValue("nextOffset").jsonPrimitive.int)
        assertEquals(2, page.getValue("total").jsonPrimitive.int)
        val tasks = (0..24).map { index -> PlannedGradleTask(NodeId("fixture", ":app", "task$index"),
            if (index == 0) emptyList() else listOf(NodeId("fixture", ":app", "task${index - 1}"))) }
        val plan = GradlePlan(node = app.id, modelDigest = BuildModelReader.digest(file), requestDigest = "request",
            contextDigest = "context", invocation = listOf(":app:jvmTest"), configuredMillis = 321, tasks = tasks)
        root.resolve(GradleProtocol.PlanDirectory + "/plan.json").apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(plan)) }
        val invalid = plan.copy(tasks = listOf(tasks.last()))
        root.resolve(GradleProtocol.PlanDirectory + "/stale.json").writeText(BuildModelReader.json.encodeToString(invalid.copy(modelDigest = "outdated")))
        root.resolve(GradleProtocol.PlanDirectory + "/legacy.json").writeText("""{"schemaVersion":1,"id":"web"}""")
        assertEquals(plan, BuildModelReader.retainedPlan(root, app.id.toString()))
        assertEquals(10, BuildModelReader.planPage(plan).getValue("nextOffset").jsonPrimitive.int)
        val receipt = RunReceipt(run = "observed", build = "fixture", requestedNodes = listOf(node.id), requestedTasks = listOf(":checks"),
            startedAtEpochMillis = 1, finishedAtEpochMillis = 2, status = ReceiptStatus.Failed,
            tasks = listOf(TaskObservation(NodeId("fixture", ":app", "jvmTest"), TaskOutcome.Failed, 1, 1)),
            modelDigest = null, modelOmission = "No source identity available")
        root.resolve(GradleProtocol.ReceiptDirectory + "/observed.json").apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(receipt)) }
        val observed = BuildModelReader.receiptPage(root, app.id.toString())
        assertEquals("recorded", observed.getValue("truth").jsonPrimitive.content)
        assertFalse(observed.getValue("matchesCurrentModel").jsonPrimitive.boolean)
        assertEquals(receipt.modelOmission, observed.getValue("receipt").jsonObject.getValue("modelOmission").jsonPrimitive.content)
        assertEquals("observed", BuildModelReader.receiptPage(root, node.id.toString()).getValue("receipt").jsonObject.getValue("run").jsonPrimitive.content)
        file.writeText(BuildModelReader.encode(declared.copy(omissions = listOf(Omission(app.id, "Changed declaration coverage")))))
        assertNull(BuildModelReader.retainedPlan(root, app.id.toString()))
        assertEquals("unavailable", BuildModelReader.receiptPage(root, app.id.toString(), "absent").getValue("truth").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { BuildModelReader.modelPage(root, limit = 0) }
        assertFailsWith<IllegalArgumentException> { BuildModelReader.planPage(plan, offset = -1) }
        file.writeText(BuildModelReader.encode(declared))
        root.resolve(GradleProtocol.PlanDirectory + "/plan.json").writeText(BuildModelReader.json.encodeToString(invalid))
        assertFailsWith<IllegalArgumentException> { BuildModelReader.retainedPlan(root, app.id.toString()) }
    }

    @Test fun `large input collections remain reachable through bounded node pages`() {
        val inputs = (0..116).map { index -> FileInput(FileInputKind.Tree, "source$index", (0..21).map { "include$it" }) }
        val declared = node.copy(inputs = listOf(FileSet(emptyList(), declarations = inputs)))
        root.resolve(BuildModelReader.RelativePath).apply { parentFile.mkdirs(); writeText(BuildModelReader.encode(model().copy(nodes = listOf(declared)))) }
        val details = BuildModelReader.nodeDetails(root, node.id.toString())
        val path = "/node/inputs/0/declarations"
        assertTrue(details.getValue("collections").jsonArray.any { it.jsonObject["path"]?.jsonPrimitive?.content == path })
        val all = mutableListOf<JsonElement>()
        var offset = 0
        do {
            val page = BuildModelReader.nodeDetails(root, node.id.toString(), path, offset)
            all.addAll(page.getValue("items").jsonArray)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(inputs.map { it.identity }, all.map { it.jsonObject.getValue("identity").jsonPrimitive.content })
        val nested = BuildModelReader.nodeDetails(root, node.id.toString(), "$path/116/includes", 10)
        assertEquals((10..19).map { "include$it" }, nested.getValue("items").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(20, nested.getValue("nextOffset").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { BuildModelReader.nodeDetails(root, node.id.toString(), offset = 10) }
    }

    @Test fun `schema size and duplicate identities fail closed`() {
        val obsolete = BuildModelReader.json.parseToJsonElement(BuildModelReader.encode(model().copy(schema = 2))).jsonObject
        val failure = assertFailsWith<IllegalArgumentException> {
            BuildModelReader.decode(JsonObject(obsolete + ("nodes" to JsonArray(listOf(buildJsonObject { put("worker", "legacy") })))).toString())
        }
        assertTrue(failure.message.orEmpty().contains("exportBuildModel"))
        assertFailsWith<IllegalArgumentException> { BuildModelReader.decode(" ".repeat(BuildModelReader.MaxBytes + 1)) }
        assertFailsWith<IllegalArgumentException> { BuildModelReader.decode(BuildModelReader.encode(model().copy(nodes = listOf(node, node)))) }
    }

    @Test fun `staleness follows content rather than modification times`() {
        val input = root.resolve("build.gradle.kts").apply { writeText("first") }
        val output = root.resolve(BuildModelReader.RelativePath).apply { parentFile.mkdirs() }
        output.writeText(BuildModelReader.encode(model().copy(declarations = listOf(DeclarationInput("fixture", input.name, BuildModelReader.digest(input))))))
        assertEquals(node, BuildModelReader.node(root, node.id.toString()))
        val time = input.lastModified()
        input.writeText("other")
        input.setLastModified(time)
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
        val details = BuildModelReader.nodeDetails(root, node.id.toString())
        assertEquals("stale", details.getValue("truth").jsonPrimitive.content)
        assertTrue(details.getValue("stale").jsonPrimitive.boolean)
        assertEquals(listOf("fixture:build.gradle.kts"), details.getValue("changedDeclarations").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stale", BuildModelReader.modelPage(root).getValue("truth").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { BuildModelReader.retainedPlan(root, node.id.toString()) }
        assertEquals("unavailable", BuildModelReader.receiptPage(root, node.id.toString()).getValue("truth").jsonPrimitive.content)
        input.writeText("first")
        input.setLastModified(time + 1000)
        assertEquals(node, BuildModelReader.node(root, node.id.toString()))
    }

    @Test fun `stale changed declarations are paged without weakening path validation`() {
        val files = (0..16).map { index -> root.resolve("input$index.gradle.kts").apply { writeText("before") } }
        val inputs = files.map { DeclarationInput("fixture", it.name, BuildModelReader.digest(it)) }
        val output = root.resolve(BuildModelReader.RelativePath).apply { parentFile.mkdirs() }
        output.writeText(BuildModelReader.encode(model().copy(declarations = inputs)))
        files.forEach { it.writeText("after") }
        val details = BuildModelReader.nodeDetails(root, node.id.toString())
        assertEquals(10, details.getValue("changedDeclarations").jsonArray.size)
        assertTrue(details.getValue("collections").jsonArray.any { it.jsonObject["path"]?.jsonPrimitive?.content == "/changedDeclarations" })
        val tail = BuildModelReader.nodeDetails(root, node.id.toString(), "/changedDeclarations", 10)
        assertEquals(7, tail.getValue("items").jsonArray.size)
        assertEquals("stale", tail.getValue("truth").jsonPrimitive.content)
        output.writeText(BuildModelReader.encode(model().copy(declarations = listOf(inputs.first().copy(path = "../outside")))))
        assertFailsWith<IllegalArgumentException> { BuildModelReader.nodeDetails(root, node.id.toString()) }
        assertFailsWith<IllegalArgumentException> { BuildModelReader.modelPage(root) }
    }

    @Test fun `node identities preserve separators and round trip`() {
        val values = listOf("fixture:foo-bar", "fixture:foo_bar", "fixture:foo:bar")
        assertEquals(values, values.map { NodeId.parse(it).toString() })
        assertEquals(3, values.map(NodeId::parse).toSet().size)
        assertEquals(model(), BuildModelReader.decode(BuildModelReader.encode(model())))
    }

    @Test fun `membership and compiled build logic invalidate declared facts`() {
        root.resolve("build.gradle.kts").writeText("plugins {}")
        val implementation = root.resolve("dependeasy/gradle/src/main/kotlin/Kernel.kt").apply {
            parentFile.mkdirs(); writeText("class Kernel")
        }
        val rootImplementation = root.resolve("buildSrc/src/main/kotlin/Handles.kt").apply {
            parentFile.mkdirs(); writeText("class Handles")
        }
        val pins = root.resolve("dependeasy/toolchain/src/main/kotlin/Versions.kt").apply {
            parentFile.mkdirs(); writeText("object Versions")
        }
        val options = root.resolve("dependeasy/toolchain/src/contracts/kotlin/BuildHandles.kt").apply {
            parentFile.mkdirs(); writeText("class BuildOption")
        }
        fun export() {
            val files = BuildDeclarationInputs.files(root).map {
                DeclarationInput("fixture", it.relativeTo(root).invariantSeparatorsPath, BuildModelReader.digest(it))
            }
            root.resolve(BuildModelReader.RelativePath).apply {
                parentFile.mkdirs(); writeText(BuildModelReader.encode(model().copy(declarations = files +
                    DeclarationInput("fixture", ".", BuildDeclarationInputs.membership(root), DeclarationInputKind.Membership))))
            }
        }
        export()
        BuildModelReader.read(root)
        implementation.writeText("class Kernel { fun changed() = true }")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
        export()
        rootImplementation.writeText("class Handles { fun changed() = true }")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
        export()
        pins.writeText("object Versions { const val Changed = true }")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
        export()
        options.writeText("class BuildOption { val changed = true }")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
        export()
        root.resolve("dependeasy/toolchain/src/contracts/kotlin/NewHandle.kt").writeText("class NewHandle")
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
        export()
        root.resolve("new/build.gradle.kts").apply { parentFile.mkdirs(); writeText("plugins {}") }
        assertFailsWith<IllegalArgumentException> { BuildModelReader.read(root) }
    }
}
