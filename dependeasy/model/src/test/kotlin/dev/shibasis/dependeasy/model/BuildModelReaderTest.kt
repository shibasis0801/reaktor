package dev.shibasis.dependeasy.model

import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class BuildModelReaderTest {
    @TempDir lateinit var root: File
    private val node = Node(NodeId("fixture", ":", "checks"), NodeKind.Target, "checks", Effect.Verify,
        HostRequirement(), SourceSite("fixture", "build.gradle.kts", 3), KernelRef("FixtureTask"), Invocation.Gradle(listOf(":checks")))
    private fun model() = BuildModel(producer = Producer(version = "fixture"), builds = listOf(BuildIdentity("fixture", ".")), nodes = listOf(node))

    @Test fun `schema size and duplicate identities fail closed`() {
        assertFailsWith<IllegalArgumentException> { BuildModelReader.decode(BuildModelReader.encode(model().copy(schema = 1))) }
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
        input.writeText("first")
        input.setLastModified(time + 1000)
        assertEquals(node, BuildModelReader.node(root, node.id.toString()))
    }

    @Test fun `node identities preserve separators and round trip`() {
        val values = listOf("fixture:foo-bar", "fixture:foo_bar", "fixture:foo:bar")
        assertEquals(values, values.map { NodeId.parse(it).toString() })
        assertEquals(3, values.map(NodeId::parse).toSet().size)
        assertEquals(model(), BuildModelReader.decode(BuildModelReader.encode(model())))
    }
}
