package dev.shibasis.reaktor.tooling

import java.io.File
import java.nio.file.Files

data class SourceDeclaration(val qualifiedName: String, val file: File, val line: Int)

/** An explicit-root declaration index. Ambiguous expect/actual declarations stay ambiguous. */
class KotlinSourceIndex private constructor(private val declarations: Map<String, List<SourceDeclaration>>) {
    fun find(qualifiedName: String): List<SourceDeclaration> = declarations[qualifiedName].orEmpty()

    companion object {
        private val excluded = setOf(".git", ".gradle", ".kotlin", "build", "node_modules", "Pods", "dist", "generated", "ts", "cpp")
        private val declaration = Regex("^\\s*(?:(?:public|private|internal|protected|open|final|abstract|sealed|data|enum|annotation|value|inner|expect|actual|external)\\s+)*(?:class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")

        fun read(roots: List<File>): KotlinSourceIndex {
            val entries = linkedMapOf<String, MutableList<SourceDeclaration>>()
            roots.map(File::getCanonicalFile).distinct().filter(File::isDirectory).forEach { root ->
                root.walkTopDown().onEnter { it.name !in excluded && !Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile && it.extension == "kt" && it.length() <= 2_000_000 && !Files.isSymbolicLink(it.toPath()) }
                    .take(30_000).forEach { file ->
                        var pkg = ""
                        var depth = 0
                        kotlinCodeOnly(file.readText()).lineSequence().forEachIndexed { index, line ->
                            if (line.trimStart().startsWith("package ")) pkg = line.trim().removePrefix("package ").substringBefore(';').trim()
                            val name = if (depth == 0) declaration.find(line)?.groupValues?.get(1) else null
                            if (name != null && pkg.isNotBlank()) {
                                val fqn = "$pkg.$name"
                                entries.getOrPut(fqn) { mutableListOf() }.add(SourceDeclaration(fqn, file, index + 1))
                            }
                            depth += line.count { it == '{' } - line.count { it == '}' }
                        }
                    }
            }
            return KotlinSourceIndex(entries.mapValues { (_, values) -> values.distinctBy { it.file.path to it.line }.sortedBy { it.file.path } })
        }
    }
}

/** Keep line numbers/braces while removing comments and quoted text from declaration matching. */
private fun kotlinCodeOnly(source: String): String {
    val output = source.toCharArray()
    var index = 0
    var block = 0
    var quote: String? = null
    var lineComment = false
    fun erase(count: Int) { repeat(count) { if (index < output.size) {
        if (output[index] != '\n') output[index] = ' '
        index++
    } } }
    while (index < source.length) {
        when {
            lineComment -> if (source[index] == '\n') { lineComment = false; index++ } else erase(1)
            block > 0 -> when {
                source.startsWith("/*", index) -> { block++; erase(2) }
                source.startsWith("*/", index) -> { block--; erase(2) }
                else -> erase(1)
            }
            quote != null -> when {
                quote != "\"\"\"" && source[index] == '\\' -> erase(2)
                source.startsWith(requireNotNull(quote), index) -> { val count = requireNotNull(quote).length; quote = null; erase(count) }
                else -> erase(1)
            }
            source.startsWith("//", index) -> { lineComment = true; erase(2) }
            source.startsWith("/*", index) -> { block = 1; erase(2) }
            source.startsWith("\"\"\"", index) -> { quote = "\"\"\""; erase(3) }
            source[index] in setOf('\'', '"') -> { quote = source[index].toString(); erase(1) }
            else -> index++
        }
    }
    return String(output)
}
