package dev.shibasis.dependeasy.analysis

import dev.shibasis.dependeasy.process.ProcessTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "The isolated compiler writes host-specific analysis classes")
abstract class KotlinAnalysisCompile : ProcessTask() {
    @get:Classpath abstract val compilerClasspath: ConfigurableFileCollection
    @get:Classpath abstract val runtimeClasspath: ConfigurableFileCollection
    @get:Internal abstract val classesDirectory: DirectoryProperty
    @get:Input abstract val bytecode: Property<Int>

    @get:OutputFile val compilerArgumentsFile: File get() = classesDirectory.get().asFile.resolveSibling("compiler.args")

    override fun commandArguments(): List<String> = listOf("@${compilerArgumentsFile.path}")

    override fun executeKernel() {
        val command = KotlinCompiler.arguments(
            compilerClasspath.asPath, runtimeClasspath.asPath, classesDirectory.get().asFile.path,
            bytecode.get(), sourceFiles.files.filter { it.extension == "kt" }.map(File::getPath).sorted(),
        )
        compilerArgumentsFile.parentFile.mkdirs()
        compilerArgumentsFile.writeText(command.joinToString("\n", postfix = "\n") { argument ->
            "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        })
        super.executeKernel()
    }
}

internal object KotlinCompiler {
    fun arguments(compiler: String, runtime: String, destination: String, bytecode: Int, sources: List<String>) =
        listOf("-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", bytecode.toString(), "-classpath", runtime, "-d", destination) + sources
}
