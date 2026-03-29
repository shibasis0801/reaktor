package dev.shibasis.dependeasy.settings

import dev.shibasis.dependeasy.utils.gitDependency
import dev.shibasis.dependeasy.utils.includeWithPath
import org.gradle.api.GradleException
import org.gradle.api.initialization.Settings
import java.io.File

internal class NativeBootstrap(
    private val settings: Settings,
) {
    private val rootDir = settings.rootDir
    private val githubDir = rootDir.resolve(".github_modules").apply(File::mkdirs)

    fun bootstrap() {
        linkFlatBuffers()
        linkHermes()
    }

    private fun linkFlatBuffers(
        repoUrl: String = "https://github.com/shibasis0801/flatbuffers.git",
    ) {
        githubDir.gitDependency(repoUrl)
        val buildDirectory = githubDir.resolve("flatbuffers")
        val flatc = if (isWindows()) {
            buildDirectory.resolve("Debug/flatc.exe")
        } else {
            buildDirectory.resolve("flatc")
        }

        if (!flatc.exists()) {
            if (isWindows()) {
                runCommand(buildDirectory, cmakeExecutable(), "-G", "Visual Studio 17 2022")
                runCommand(buildDirectory, cmakeExecutable(), "--build", ".")
            } else {
                runCommand(buildDirectory, cmakeExecutable(), "-G", "Unix Makefiles")
                runCommand(buildDirectory, "make", "-j")
            }
        }

        require(flatc.exists()) { "Failed to build flatc at ${flatc.absolutePath}" }
        settings.includeWithPath("flatbuffers-kotlin", ".github_modules/flatbuffers/kotlin/flatbuffers-kotlin")
    }

    private fun linkHermes(
        repoUrl: String = "https://github.com/facebook/hermes.git",
    ) {
        githubDir.gitDependency(repoUrl)
        val hermesSourceDirectory = githubDir.resolve("hermes")
        val buildDirectory = githubDir.resolve("hermes/debug").apply(File::mkdirs)
        val hermesCompiler = if (isWindows()) {
            buildDirectory.resolve("bin/Debug/hermesc.exe")
        } else {
            buildDirectory.resolve("bin/hermesc")
        }
        val jsiHeader = hermesSourceDirectory.resolve("API/jsi/jsi/jsi.h")

        require(jsiHeader.exists()) { "${jsiHeader.absolutePath} does not exist." }

        if (shouldRebuildHermes(buildDirectory, hermesCompiler)) {
            buildDirectory.deleteRecursively()
            buildDirectory.mkdirs()
            if (isWindows()) {
                runCommand(
                    buildDirectory,
                    cmakeExecutable(),
                    "-G", "Visual Studio 17 2022",
                    "-A", "x64",
                    "-DCMAKE_BUILD_TYPE=Debug",
                    hermesSourceDirectory.absolutePath,
                )
                runCommand(buildDirectory, cmakeExecutable(), "--build", ".")
            } else {
                runCommand(
                    buildDirectory,
                    cmakeExecutable(),
                    "-G", "Ninja",
                    "-DHERMES_BUILD_APPLE_FRAMEWORK=ON",
                    "-DCMAKE_BUILD_TYPE=Debug",
                    "-DCMAKE_MAKE_PROGRAM=${ninjaExecutable()}",
                    hermesSourceDirectory.absolutePath,
                )
                runCommand(buildDirectory, ninjaExecutable(), "hermesc")
            }
        }

        require(hermesCompiler.exists()) { "Failed to build Hermes at ${hermesCompiler.absolutePath}" }
    }

    private fun shouldRebuildHermes(
        buildDirectory: File,
        hermesCompiler: File,
    ): Boolean {
        if (!hermesCompiler.exists()) {
            return true
        }
        val importHostCompilers = buildDirectory.resolve("ImportHostCompilers.cmake")
        if (!importHostCompilers.exists()) {
            return true
        }
        return !importHostCompilers.readText().contains(hermesCompiler.absolutePath)
    }

    private fun runCommand(
        workingDirectory: File,
        vararg command: String,
    ) {
        val exitCode = ProcessBuilder(command.toList())
            .directory(workingDirectory)
            .inheritIO()
            .start()
            .waitFor()

        if (exitCode != 0) {
            throw GradleException("Command failed (${command.joinToString(" ")}) with exit code $exitCode")
        }
    }

    private fun cmakeExecutable(): String = findExecutable(
        fromPath = "cmake",
        fallbacks = listOf("/usr/local/bin/cmake", "/opt/homebrew/bin/cmake"),
    )

    private fun ninjaExecutable(): String = findExecutable(
        fromPath = "ninja",
        fallbacks = listOf("/opt/homebrew/bin/ninja", "/usr/local/bin/ninja"),
    )

    private fun findExecutable(
        fromPath: String,
        fallbacks: List<String>,
    ): String {
        resolveFromPath(fromPath)?.let { return it }
        return fallbacks.firstOrNull { File(it).canExecute() }
            ?: throw GradleException("Unable to find executable '$fromPath'")
    }

    private fun resolveFromPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path
            .split(File.pathSeparatorChar)
            .asSequence()
            .map(::File)
            .map { it.resolve(name) }
            .firstOrNull(File::canExecute)
            ?.absolutePath
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").contains("Windows", ignoreCase = true)
}
