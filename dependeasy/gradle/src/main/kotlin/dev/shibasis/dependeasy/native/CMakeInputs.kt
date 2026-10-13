package dev.shibasis.dependeasy.native

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import java.io.File

internal fun Project.cmakeKernelSources(): FileCollection = rootProject.fileTree(".") {
    include("root.cmake", "cmake/**/*.cmake", "dependeasy/cmake/**/*.cmake")
}

/** Host and mobile builds snapshot the same authored native sources and vendor inputs. */
internal fun Project.cmakeSources(directory: File): FileCollection {
    fun tree(path: File, vararg patterns: String) = fileTree(path) {
        if (patterns.isNotEmpty()) include(*patterns)
        exclude("**/build/**", "**/node_modules/**", "**/.git", "**/.git/**", "**/.gradle/**", "**/.cxx/**", "**/dist/**")
    }
    val source = directory.normalize()
    return if (source == projectDir.normalize()) files(
        source.resolve("CMakeLists.txt"),
        tree(source.resolve("cmake"), "**/*.cmake"),
        tree(source.resolve("src"), "**/cpp/**", "**/cinterop/**"),
        tree(source.resolve("cpp")),
    ) else tree(source)
}
