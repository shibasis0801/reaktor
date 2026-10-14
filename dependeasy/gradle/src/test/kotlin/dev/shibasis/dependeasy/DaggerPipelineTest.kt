package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.dagger.GenerateDaggerTask
import dev.shibasis.dependeasy.dagger.VerifyDaggerTask
import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DaggerPipelineTest {
    @TempDir lateinit var root: File
    @Test fun `pipeline is data only and prunes local dependency closure`() {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        fun node(name: String, host: HostRequirement = HostRequirement()) = Node(NodeId("fixture", ":", name), NodeKind.Target, name,
            Effect.Verify, host, null, null, Invocation.Gradle(listOf(":$name")))
        val local = node("local", HostRequirement(execution = ExecutionHost.Local))
        val parent = node("parent")
        val pr = node("pr")
        val task = project.tasks.create("generate", GenerateDaggerTask::class.java).apply {
            buildName.set("fixture"); engineVersion.set("0.21.8"); browserImage.set("fixture-browser")
            gradleProperties.set(mapOf("fixture.option" to "false"))
            toolchainContract.set(BuildModelReader.json.encodeToString(DaggerToolchainContract(javaImage = "fixture-jdk", gradleUrl = "fixture", gradleSha256 = "0".repeat(64), gradleArchive = "fixture",
                androidSdk = AndroidSdkContract(directory = "fixture", commandLineTools = "fixture", downloadUrl = "fixture", sha256 = "0".repeat(64), packages = emptyList()))))
            declarations.set(listOf(local, parent, pr).map(BuildModelReader::encode))
            edges.set(listOf(BuildModelReader.json.encodeToString<Edge>(Edge.Includes(parent.id, local.id))))
            outputFile.set(root.resolve("dagger/pipeline.json"))
        }
        task.generate()
        val pipeline = BuildModelReader.json.decodeFromString<DaggerPipelineContract>(task.outputFile.get().asFile.readText())
        assertEquals(listOf(pr.id), pipeline.targets.map { it.id })
        assertEquals(mapOf("ORG_GRADLE_PROJECT_fixture.option" to "false"), pipeline.environment)
        assertEquals(setOf("pipeline.json"), root.resolve("dagger").list()!!.toSet())
        val verify = project.tasks.create("verify", VerifyDaggerTask::class.java).apply {
            expected.set(task.outputFile); actual.set(root.resolve("pipeline-copy.json"))
        }
        task.outputFile.get().asFile.copyTo(verify.actual.get().asFile)
        verify.check()
        verify.actual.get().asFile.appendText("stale")
        assertFailsWith<IllegalArgumentException> { verify.check() }
    }
}
