package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.files.deleteTreeSafely
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject

@CacheableTask
abstract class ProtobufTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiler: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val grpcPlugin: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty
    @get:Input abstract val entry: Property<String>
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:Internal abstract val buildRoot: DirectoryProperty

    @TaskAction fun generate() {
        val protoc = compiler.singleFile
        val grpc = grpcPlugin.singleFile
        listOf(protoc, grpc).forEach { check(it.setExecutable(true) || it.canExecute()) }
        val output = destination.get().asFile
        output.deleteTreeSafely(within = buildRoot.get().asFile)
        output.mkdirs()
        exec.exec {
            commandLine(protoc.absolutePath,
                "--plugin=protoc-gen-grpc-java=${grpc.absolutePath}",
                "--proto_path=${sources.get().asFile.absolutePath}",
                "--java_out=${output.absolutePath}", "--grpc-java_out=${output.absolutePath}",
                sources.file(entry).get().asFile.absolutePath)
        }.assertNormalExitValue()
    }
}
