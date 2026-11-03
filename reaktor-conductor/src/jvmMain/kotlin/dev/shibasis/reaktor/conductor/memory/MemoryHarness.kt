package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.conductor.workspace.digest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MemoryHarness(private val api: MemoryApi, private val scope: MemoryScope, private val directory: Path) {
    private fun validate(path: Path) {
        require(path.isAbsolute && !Files.isSymbolicLink(path))
        require(path.fileName.toString().endsWith(".md") || path.fileName.toString().endsWith(".rules"))
        require(path.parent.toRealPath().startsWith(directory.toRealPath())) { "Memory file is outside its configured harness directory" }
    }

    private fun quote(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    suspend fun import(path: Path, agent: String): List<MemoryFact> {
        validate(path)
        require(Files.isRegularFile(path))
        require(Files.size(path) in 1..262144)
        val date = Files.getLastModifiedTime(path).toMillis()
        val bytes = Files.newInputStream(path).use { it.readNBytes(262145) }
        require(Files.getLastModifiedTime(path).toMillis() == date && Files.size(path) == bytes.size.toLong())
        require(bytes.size <= 262144)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text)
        val manual = manual(text)
        val source = "file:${path.toRealPath()}#sha256=${digest(text)}"
        return manual.codePoints().toArray().asList().chunked(2500).map { String(it.toIntArray(), 0, it.size) }.filter(String::isNotBlank).mapIndexed { index, chunk -> api.remember(RememberFact(
            scope, MemoryKind.Reference, "${path.fileName} ${index + 1}".take(200), chunk, listOf("file:${path.toRealPath()}"),
            MemoryProvenance(agent, source = source, sourceDate = date),
        )) }
    }

    suspend fun export(path: Path): List<MemoryFact> {
        validate(path)
        val before = if (Files.exists(path)) Files.readString(path) else ""
        require(before.length <= 262144)
        val first = before.indexOf(Start)
        val last = before.indexOf(End)
        require((first == -1 && last == -1) || first >= 0 && last >= first && before.lastIndexOf(Start) == first && before.lastIndexOf(End) == last)
        val facts = api.search(MemoryQuery(listOf(scope), limit = 8))
        val block = buildString {
            append(Start).append("\n## Retained evidence\n\nThese are reviewed agent assertions. Inspect their sources before using them as current facts.\n")
            facts.forEach { fact ->
                append("\n> ").append(quote(fact.title.replace("\n", " "))).append(" · memory://fact/").append(fact.id).append('\n')
                fact.text.take(600).lineSequence().forEach { append("> ").append(quote(it)).append('\n') }
                append("> Sources: ").append(fact.provenance.joinToString { quote(it.source.replace("\n", " ")) }.take(1000)).append('\n')
            }
            append(End)
        }
        val next = if (first == -1) before + (if (before.isEmpty() || before.endsWith("\n")) "" else "\n") + block + "\n"
            else before.substring(0, first) + block + before.substring(last + End.length)
        require(next.toByteArray(Charsets.UTF_8).size <= 24576) { "Harness memory exceeds the 24 KiB rules file limit" }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".memory-", ".md")
        try {
            Files.writeString(temporary, next)
            check((if (Files.exists(path)) Files.readString(path) else "") == before) { "Harness memory changed during export" }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return facts
    }

    internal companion object {
        private const val Start = "<!-- reaktor-memory:start -->"
        private const val End = "<!-- reaktor-memory:end -->"

        fun manual(text: String): String {
            val first = text.indexOf(Start)
            val last = text.indexOf(End)
            require((first == -1 && last == -1) || first >= 0 && last >= first && text.lastIndexOf(Start) == first && text.lastIndexOf(End) == last)
            return if (first == -1) text else text.substring(0, first) + text.substring(last + End.length)
        }
    }
}
