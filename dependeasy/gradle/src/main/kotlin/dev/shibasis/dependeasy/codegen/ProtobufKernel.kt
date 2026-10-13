package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal class ProtobufKernel(private val project: Project, private val entry: String, private val source: Any) {
    fun install() {
        val compiler = project.configurations.create("protocTool")
        val plugin = project.configurations.create("grpcPluginTool")
        project.dependencies.apply {
            add(compiler.name, "${Versions.Tooling.Protoc}:${classifier()}@exe")
            add(plugin.name, "${Versions.Tooling.GrpcCompiler}:${classifier()}@exe")
            listOf(Versions.Tooling.Protobuf, Versions.Tooling.GrpcProtobuf, Versions.Tooling.GrpcStub,
                Versions.Tooling.GrpcOkHttp).forEach { add("api", it) }
            add("compileOnly", Versions.Tooling.JavaxAnnotations)
        }
        val name = entry.substringBeforeLast('.').replaceFirstChar(Char::uppercaseChar)
        val generation = project.tasks.register<ProtobufTask>("generate${name}Proto") {
            this.compiler.from(compiler)
            grpcPlugin.from(plugin)
            sources.set(project.layout.projectDirectory.dir(project.file(source).relativeTo(project.projectDir).path))
            this.entry.set(this@ProtobufKernel.entry)
            destination.set(project.layout.buildDirectory.dir("generated/proto"))
            buildRoot.set(project.layout.buildDirectory)
        }
        project.extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main").java
            .srcDir(generation.flatMap { it.destination })
        project.tasks.matching { it.name == "compileKotlin" }.configureEach { dependsOn(generation) }
    }

    private fun classifier(): String {
        val os = System.getProperty("os.name").lowercase()
        require(os.contains("mac") || os.contains("linux")) { "Protobuf tools support macOS and Linux" }
        val cpu = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "aarch_64" else "x86_64"
        return "${if (os.contains("mac")) "osx" else "linux"}-$cpu"
    }
}
