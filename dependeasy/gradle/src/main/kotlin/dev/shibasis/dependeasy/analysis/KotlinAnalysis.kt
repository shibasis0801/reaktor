package dev.shibasis.dependeasy.analysis

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.handles.artifact
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.ExportBuildNodesTask
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

fun Project.kotlinAnalysis(
    sources: String = "tools/kotlin-analysis",
    launcher: String = "$sources/run",
    tests: String = "src/kotlinSemanticTest/kotlin",
) {
    repositories.exclusiveContent {
        forRepository { repositories.maven { url = uri("https://packages.jetbrains.team/maven/p/kt/dev"); name = "KotlinAnalysisStandalone" } }
        filter { includeVersionByRegex("org\\.jetbrains\\.kotlin", ".*", Regex.escape(Versions.Kotlin.AnalysisStandaloneVersion)) }
    }
    val compiler = configurations.create("kotlinAnalysisCompiler") { isCanBeConsumed = false }
    val runtime = configurations.create("kotlinAnalysisRuntime") { isCanBeConsumed = false }
    val metadata = configurations.create("kotlinAnalysisTestMetadata") { isCanBeConsumed = false; isTransitive = false }
    dependencies.add(compiler.name, Versions.Kotlin.AnalysisCompiler)
    dependencies.add(runtime.name, Versions.Kotlin.AnalysisStandalone)
    dependencies.add(runtime.name, Versions.Kotlin.SerializationJsonJvm)
    dependencies.add(metadata.name, "${Versions.Kotlin.Stdlib}:all")
    val java = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    val compile = tasks.register<KotlinAnalysisCompile>("compileKotlinAnalysis") {
        group = "build"
        workingDirectory.set(layout.projectDirectory)
        executable.set(java.map { it.executablePath.asFile.path })
        toolVersion.set(Versions.Kotlin.AnalysisStandaloneVersion)
        compilerClasspath.from(compiler)
        runtimeClasspath.from(runtime)
        bytecode.set(Versions.SDK.Java.asInt)
        classesDirectory.set(layout.buildDirectory.dir("kotlin-analysis/classes"))
        sourceFiles.from(fileTree(sources) { include("**/*.kt") }, runtime, compiler)
        outputDirectories.from(classesDirectory)
    }
    val projection = tasks.register<ExportBuildNodesTask>("kotlinAnalysisModel") {
        group = "dependeasy"
        description = "Export analysis metadata under its owning project"
        buildName.set(rootProject.name)
        nodes.convention(emptyList())
        nodes.finalizeValueOnRead()
        outputFile.set(layout.buildDirectory.file("dependeasy/kotlin-analysis-model.json"))
    }
    BuildWorkspace.get(this).reference(this, compile, null, projection = projection)
    val archive = tasks.register<Zip>("kotlinSemanticDistZip") {
        dependsOn(compile)
        archiveFileName.set("kotlin-semantics.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        from(compile.flatMap { it.classesDirectory }) { into("classes") }
        from(runtime) { into("lib") }
        from(launcher)
    }
    val target = extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinJvmTarget::class.java).single()
    val compilation = target.compilations.create("kotlinSemantic") {
        associateWith(target.compilations.getByName("test"))
        defaultSourceSet.kotlin.setSrcDirs(listOf(tests))
    }
    val executionClasspath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    val verify = tasks.register<Test>("kotlinSemanticTest") {
        group = "verification"
        dependsOn(archive)
        testClassesDirs = compilation.output.classesDirs
        classpath = executionClasspath
        javaLauncher.set(java)
        jvmArgumentProviders.add(KotlinAnalysisArguments(archive.flatMap { it.archiveFile },
            executionClasspath.filter { it.name == "kotlin-stdlib-${Versions.SDK.Kotlin}.jar" }, metadata))
    }
    node(archive, OutputKind.JvmDistribution).consumes(artifact(compile) { it.classesDirectory })
    node(verify, OutputKind.VerificationResults).consumes(artifact(archive) { it.archiveFile })
    publishOutput(OutputKind.JvmDistribution, archive.flatMap { it.archiveFile }, producer = archive)
}
