package dev.shibasis.dependeasy.model

import java.io.File

data class JvmRuntime(val jars: List<File>, val modules: List<String>)

object JvmLaunchKernel {
    fun classpath(paths: List<String>, output: File) {
        output.parentFile.mkdirs()
        output.writeText(paths.joinToString("\n"))
    }

    fun arguments(environment: ProgramEnvironmentSpec, root: File): List<String> = environment.inputs.flatMap { input ->
        val value = if (input.option.value.isBlank() && input.whenBlank == ProgramFallback.BuildRoot)
            root.absolutePath else input.option.value
        require(!input.required || value.isNotBlank()) { "Program needs option ${input.option.name}" }
        when (input.option.type) {
            OptionType.Boolean -> {
                require(input.prefix.isNotBlank()) { "A boolean program argument needs its flag" }
                if (value.toBooleanStrict()) listOf(input.prefix) else emptyList()
            }
            OptionType.String, OptionType.Integer ->
                if (value.isNotEmpty()) listOf(input.prefix + value) else emptyList()
        }
    }

    fun packaged(directory: File, packageName: String): JvmRuntime {
        val release = directory.walkTopDown().single { it.isFile && it.name == "release" &&
            it.readLines().any { line -> line.startsWith("MODULES=") } }
        val modules = release.readLines().single { it.startsWith("MODULES=") }
            .substringAfter('=').trim('"').split(' ')
        val configuration = directory.walkTopDown().single { it.name == "$packageName.cfg" }
        val jars = configuration.readLines().filter { it.startsWith("app.classpath=") }.map {
            File(configuration.parentFile, it.substringAfter("\$APPDIR/"))
        }
        check(jars.isNotEmpty() && jars.all(File::isFile)) { "Packaged runtime classpath is incomplete" }
        return JvmRuntime(jars, modules)
    }

    fun command(executable: String, classpath: String, mainClass: String, arguments: List<String>,
        jvmArguments: List<String>, relativeClasspath: String?): String {
        val cp = if (relativeClasspath != null) "\"\$here/$relativeClasspath/*\"" else quote(classpath)
        val java = (listOf(executable) + jvmArguments).joinToString(" ", transform = ::quote)
        return "$java -cp $cp " + (listOf(mainClass) + arguments).joinToString(" ", transform = ::quote)
    }

    fun launcher(command: String, relative: Boolean, output: File) {
        output.parentFile.mkdirs()
        val base = if (relative) "here=\$(cd \"\$(dirname \"\$0\")/..\" && pwd)\n" else ""
        output.writeText("#!/bin/sh\n${base}exec $command \"\$@\"\n")
        check(output.setExecutable(true)) { "Could not enable launcher $output" }
    }

    private fun quote(value: String) = "'${value.replace("'", "'\"'\"'")}'"
}
