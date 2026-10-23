package dev.shibasis.dependeasy.module

import org.gradle.api.Project
import dev.shibasis.dependeasy.common.JvmDependencies
import org.gradle.kotlin.dsl.dependencies
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.verification
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.handles.OutputHandle
import dev.shibasis.dependeasy.handles.outputFiles
import org.gradle.kotlin.dsl.named
import dev.shibasis.dependeasy.verification.dependencyBoundary
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.verification.entryTests
import dev.shibasis.dependeasy.verification.componentTests
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import dev.shibasis.dependeasy.provenance.identity
import dev.shibasis.dependeasy.workspace.declarationSite

/** Product declarations name libraries and producers; kernels own classpaths and compiler wiring. */
class JvmModule internal constructor(private val project: Project, bytecode: Int) {
    init { project.jvmConfiguration(bytecode) }
    internal val verifications = mutableListOf<NodeHandle>()
    internal val generatedResources = mutableListOf<NodeHandle>()
    private var testsDeclared = false

    fun tests(environment: TestEnvironment = TestEnvironment(), run: Run = Run.Default,
        inputs: List<OutputHandle> = emptyList()): NodeDeclaration {
        require(!testsDeclared) { "A JVM suite is declared once" }
        testsDeclared = true
        project.componentTests(Suite.Jvm, environment, run)
        val task = project.tasks.named<org.gradle.api.tasks.testing.Test>(org.gradle.api.plugins.JavaPlugin.TEST_TASK_NAME)
        val node = project.node(task, OutputKind.VerificationResults)
        inputs.forEach { output -> BuildWorkspace.get(project).consume(node, output) }
        task.configure { inputs.forEach { output -> this.inputs.files(project.outputFiles(output)) } }
        val part = project.dependencies.project(mapOf("path" to project.path)) as org.gradle.api.artifacts.ProjectDependency
        val output = project.verification(part, Verification.Jvm)
        val declaration = WorkspaceScope(project, BuildWorkspace.get(project)).verify(run = run) { include(output) }
        return NodeDeclaration { name -> declaration.declare(name).also(verifications::add) }
    }

    fun boundary(policy: KernelSpec.DependencyBoundary, verification: Verification,
        description: String? = null) = NodeDeclaration { name ->
        val task = project.dependencyBoundary(name, policy, description)
        project.publishOutput(OutputKind.VerificationResults, task.flatMap { it.resultFile }, verification, producer = task)
        project.node(task, OutputKind.VerificationResults).also(verifications::add)
    }

    fun identity(fileName: String, sources: List<dev.shibasis.dependeasy.model.IdentitySource>,
        revisions: List<dev.shibasis.dependeasy.model.IdentityRevision> = emptyList(), recordTime: Boolean = false): NodeDeclaration {
        val declaration = project.identity(fileName, sources, revisions, recordTime)
        return NodeDeclaration { name -> declaration.declare(name).also(generatedResources::add) }
    }

    fun verify(environment: TestEnvironment = TestEnvironment(), run: Run = Run.Default): NodeDeclaration {
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            project.entryTests(name, Suite.Jvm, CompilationRef.Main, environment, run, site, null)
                .also(verifications::add)
        }
    }

    fun program(environment: dev.shibasis.dependeasy.model.ProgramEnvironment = dev.shibasis.dependeasy.model.ProgramEnvironment(),
        run: Run = Run.Default, sources: FileSet? = null, compilation: CompilationRef = CompilationRef.Main) =
        NodeDeclaration { name ->
            project.jvmExecution(compilation.name)
            project.componentProgram(name, environment, run, sources, compilation)
        }

    fun launcher(program: NodeHandle, output: String, arguments: List<String> = emptyList()) =
        NodeDeclaration { name -> project.node(project.jvmLauncher(name, program, output, arguments)) }

    fun distribution(program: NodeHandle, executable: String) =
        NodeDeclaration { name -> project.jvmDistribution(name, program, executable) }

    fun dependencies(configuration: JvmDependencies.() -> Unit) = project.dependencies {
        JvmDependencies(this).configuration()
    }
    fun protobuf(entry: String, source: Any = "src/main/proto") =
        dev.shibasis.dependeasy.codegen.ProtobufKernel(project, entry, source).install()
}
