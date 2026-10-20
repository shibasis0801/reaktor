package dev.shibasis.dependeasy.model

import java.io.File

object JvmTests {
    fun plan(classes: List<File>, sources: List<File>, root: File, build: String): List<ResolvedJvmTest> =
        compiledClasses(classes).filter { it.tags.isNotEmpty() }.map { compiled ->
            val file = requireNotNull(compiled.source) { "Tagged test needs SourceFile metadata: ${compiled.name}" }
            val line = requireNotNull(compiled.line?.takeIf { it > 0 }) { "Tagged test needs source line metadata: ${compiled.name}" }
            ResolvedJvmTest(compiled.name, compiled.tags, compiledSource(compiled.name, file, line, sources, root, build))
        }
}
