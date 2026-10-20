package dev.shibasis.dependeasy.model

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class ResolvedJvmEntry(val className: String, val source: SourceSite)

object JvmEntry {
    fun plan(classes: List<File>, sources: List<File>, root: File, build: String,
        ownership: FileSet? = null): ResolvedJvmEntry {
        val base = root.canonicalFile.toPath()
        val owned = ownership?.ownedPaths(root)
        val entries = compiledClasses(classes).flatMap { compiled ->
            compiled.entries.map { line -> ResolvedJvmEntry(compiled.name,
                compiledSource(compiled.name, requireNotNull(compiled.source), line, sources, root, build)) }
        }.filter { entry -> owned == null || owned.any { base.resolve(entry.source.file).normalize().startsWith(it) } }
        require(entries.size == 1) {
            "Expected exactly one compiled @Entry main; found ${entries.size}: ${entries.joinToString { it.className }}"
        }
        return entries.single()
    }

    fun sourceSite(type: Class<*>, root: File, build: String, sources: List<File>, sourceFiles: Set<String>? = null): SourceSite? {
        val compiled = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")?.use {
            ClassEntry(DataInputStream(it)).read()
        } ?: return null
        val file = compiled.source ?: return null
        val line = compiled.line ?: return null
        val namespace = compiled.name.substringBeforeLast('.', "").replace('.', '/')
        val source = sources.map { it.resolve(if (namespace.isEmpty()) file else "$namespace/$file") }
            .filter { sourceFiles?.contains(it.absolutePath) ?: it.isFile }.distinctBy { it.canonicalPath }.singleOrNull() ?: return null
        return SourceSite(build, source.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath, line)
    }

    fun benchmarks(classes: List<File>, sources: List<File>, root: File, build: String,
        ownership: FileSet): List<ResolvedJvmBenchmark> {
        val base = root.canonicalFile.toPath()
        val owned = ownership.ownedPaths(root)
        val result = compiledClasses(classes).filter { it.benchmarks.isNotEmpty() }.mapNotNull { compiled ->
            val methods = compiled.benchmarks.mapValues { (_, line) ->
                compiledSource(compiled.name, requireNotNull(compiled.source), line, sources, root, build)
            }
            if (methods.values.none { site -> owned.any { base.resolve(site.file).normalize().startsWith(it) } }) null
            else ResolvedJvmBenchmark(compiled.name, methods)
        }
        require(result.isNotEmpty()) { "Expected compiled @Benchmark methods in the owned sources" }
        return result
    }
}

@Serializable
data class ResolvedJvmBenchmark(val className: String, val methods: Map<String, SourceSite>)

private fun FileSet.ownedPaths(root: File): List<java.nio.file.Path> {
    require(paths.isNotEmpty() && kind == null && declarations.isEmpty()) {
        "Program source ownership needs explicit file or directory paths"
    }
    val base = root.canonicalFile.toPath()
    return paths.map { path ->
        require(!File(path).isAbsolute) { "Program source ownership must be relative to the build root" }
        root.resolve(path).canonicalFile.toPath().also {
            require(it.startsWith(base)) { "Program source ownership escapes the build root: $path" }
        }
    }
}

object Jmh {
    val jvmArguments: List<String> get() = listOf("-Xms1g", "-Xmx1g")

    fun arguments(spec: KernelSpec.Jmh, workloads: List<ResolvedJvmBenchmark>, report: File): List<String> {
        val methods = workloads.flatMap { workload -> workload.methods.keys.map { "${workload.className}.$it" } }.sorted()
        require(methods.isNotEmpty()) { "JMH needs compiled benchmark methods" }
        val selection = methods.joinToString("|") { "^${java.util.regex.Pattern.quote(it)}$" }
        return listOf(selection, "-f", spec.forks.toString(), "-wi", spec.warmups.toString(),
            "-i", spec.iterations.toString(), "-w", "${spec.iterationMillis}ms", "-r", "${spec.iterationMillis}ms",
            "-tu", spec.outputUnit, "-rf", "json", "-rff", report.absolutePath, "-foe", "true") +
            if (spec.allocations) listOf("-prof", "gc") else emptyList()
    }
}

internal data class CompiledClass(val name: String, val source: String?, val line: Int?,
    val entries: List<Int>, val tags: Set<TestTag>, val benchmarks: Map<String, Int>)

internal fun compiledClasses(classes: List<File>): List<CompiledClass> = classes.flatMap { directory ->
    if (directory.isDirectory) directory.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
    else listOfNotNull(directory.takeIf { it.isFile && it.extension == "class" })
}.distinctBy { it.canonicalPath }.sortedBy { it.path }.map { file ->
    file.inputStream().use { ClassEntry(DataInputStream(it)).read() }
}

internal fun compiledSource(name: String, file: String, line: Int, sources: List<File>, root: File, build: String): SourceSite {
    val namespace = name.substringBeforeLast('.', "")
    val candidates = sources.filter { source ->
        source.isFile && source.name == file &&
            (if (namespace.isEmpty()) !Regex("(?m)^\\s*package\\s").containsMatchIn(source.readText()) else
                Regex("(?m)^\\s*package\\s+${Regex.escape(namespace)}\\s*(?:;|$)").containsMatchIn(source.readText()))
    }.distinctBy { it.canonicalPath }
    require(candidates.size == 1) { "Expected one source for compiled declaration $name ($file); found ${candidates.size}" }
    return SourceSite(build, candidates.single().canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath, line)
}

private class ClassEntry(private val input: DataInputStream) {
    private lateinit var pool: Array<Any?>
    private fun text(index: Int): String = pool[index] as String
    private fun DataInputStream.attributes(read: (String, DataInputStream) -> Unit) {
        repeat(readUnsignedShort()) {
            val name = text(readUnsignedShort())
            val size = readInt()
            require(size >= 0) { "Invalid class attribute size" }
            val bytes = ByteArray(size).also(::readFully)
            read(name, DataInputStream(ByteArrayInputStream(bytes)))
        }
    }
    private fun DataInputStream.skipElement() {
        when (val tag = readUnsignedByte().toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> readUnsignedShort()
            'e' -> { readUnsignedShort(); readUnsignedShort() }
            '@' -> annotation()
            '[' -> repeat(readUnsignedShort()) { skipElement() }
            else -> error("Invalid annotation element $tag")
        }
    }
    private fun DataInputStream.annotation(): String {
        val type = text(readUnsignedShort())
        repeat(readUnsignedShort()) { readUnsignedShort(); skipElement() }
        return type
    }
    fun read(): CompiledClass {
        require(input.readInt() == 0xCAFEBABE.toInt()) { "Expected JVM class bytes" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        pool = arrayOfNulls(input.readUnsignedShort())
        var index = 1
        while (index < pool.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> pool[index] = input.readUTF()
                3, 4 -> input.readInt()
                5, 6 -> { input.readLong(); index++ }
                7 -> pool[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> error("Unsupported constant pool tag $tag")
            }
            index++
        }
        input.readUnsignedShort()
        val className = text(pool[input.readUnsignedShort()] as Int).replace('/', '.')
        input.readUnsignedShort()
        repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort()
            input.attributes { _, _ -> }
        }
        var hasLauncher = false
        val benchmarks = linkedMapOf<String, Int>()
        val lines = mutableListOf<Int>()
        var firstLine: Int? = null
        repeat(input.readUnsignedShort()) {
            val access = input.readUnsignedShort()
            val name = text(input.readUnsignedShort())
            val descriptor = text(input.readUnsignedShort())
            val publicStatic = (access and 0x0009) == 0x0009
            if (name == "main" && descriptor == "([Ljava/lang/String;)V" && publicStatic) hasLauncher = true
            var entry = false
            var benchmark = false
            var line: Int? = null
            input.attributes { attribute, bytes ->
                when (attribute) {
                    "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> repeat(bytes.readUnsignedShort()) {
                        when (bytes.annotation()) {
                            "Ldev/shibasis/reaktor/core/build/Entry;" -> entry = true
                            "Lorg/openjdk/jmh/annotations/Benchmark;" -> benchmark = true
                        }
                    }
                    "Code" -> {
                        bytes.readUnsignedShort(); bytes.readUnsignedShort()
                        val length = bytes.readInt()
                        require(length >= 0)
                        bytes.skipNBytes(length.toLong())
                        repeat(bytes.readUnsignedShort()) { bytes.skipNBytes(8) }
                        bytes.attributes { nested, table ->
                            if (nested == "LineNumberTable") repeat(table.readUnsignedShort()) {
                                table.readUnsignedShort()
                                val candidate = table.readUnsignedShort()
                                line = line?.let { minOf(it, candidate) } ?: candidate
                            }
                        }
                    }
                }
            }
            line?.takeIf { it > 0 }?.let { candidate -> firstLine = firstLine?.let { minOf(it, candidate) } ?: candidate }
            if (benchmark) benchmarks[name] = requireNotNull(line?.takeIf { it > 0 }) {
                "@Benchmark needs source line metadata: $className.$name"
            }
            if (entry) {
                require(publicStatic && name == "main" && descriptor in setOf("()V", "([Ljava/lang/String;)V")) {
                    "@Entry needs a public static main returning Unit: $className.$name$descriptor"
                }
                lines.add(requireNotNull(line?.takeIf { it > 0 }) { "@Entry needs source line metadata: $className" })
            }
        }
        var source: String? = null
        val tags = linkedSetOf<TestTag>()
        input.attributes { name, bytes ->
            when (name) {
                "SourceFile" -> source = text(bytes.readUnsignedShort())
                "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> repeat(bytes.readUnsignedShort()) {
                    when (bytes.annotation()) {
                        "Ldev/shibasis/reaktor/core/build/WindowTest;" -> tags.add(TestTag.Window)
                        "Ldev/shibasis/reaktor/core/build/HangarFlow;" -> tags.add(TestTag.HangarFlow)
                    }
                }
            }
        }
        if (lines.isNotEmpty()) {
            require(hasLauncher) { "@Entry needs a JVM main(String[]) launcher: $className" }
            requireNotNull(source) { "@Entry needs SourceFile metadata: $className" }
        }
        return CompiledClass(className, source, firstLine, lines, tags, benchmarks)
    }
}
