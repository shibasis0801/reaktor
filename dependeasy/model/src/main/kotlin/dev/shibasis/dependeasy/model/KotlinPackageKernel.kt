package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

object KotlinPackageKernel {
    const val RuntimeEntry = "runtime.mjs"
    const val RuntimeTypes = "runtime.d.mts"
    private val sideEffectImport = Regex("""^import '\./([^']+\.mjs)';$""", RegexOption.MULTILINE)

    fun plan(spec: KernelSpec.KotlinPackage): List<String> {
        require(spec.destination.paths.size == 1 && spec.dependencies.paths.size == 1 && spec.receipt.paths.size == 1)
        return listOf("node_modules", "node_modules/**")
    }

    fun dependenciesMatch(spec: KernelSpec.KotlinPackage): Boolean {
        plan(spec)
        val source = File(spec.dependencies.paths.single()).toPath()
        val link = File(spec.destination.paths.single()).parentFile.resolve("node_modules").toPath()
        return Files.isSymbolicLink(link) && link.parent.resolve(Files.readSymbolicLink(link)).normalize() == source.normalize()
    }

    fun linkDependencies(spec: KernelSpec.KotlinPackage) {
        plan(spec)
        val source = File(spec.dependencies.paths.single())
        if (!source.isDirectory || dependenciesMatch(spec)) return
        val exported = File(spec.destination.paths.single()).parentFile
        val link = exported.resolve("node_modules").toPath()
        exported.mkdirs()
        if (Files.isSymbolicLink(link)) Files.delete(link)
        else if (Files.exists(link)) link.toFile().deleteTreeSafely(within = exported)
        Files.createSymbolicLink(link, link.parent.relativize(source.toPath()))
    }

    /** Per-file output gives every Kotlin library its own barrel, and the main barrel alone imports the
     * eager initializers. The runtime entry runs those initializers and re-exports every library, so a
     * consumer still imports one Kotlin module while its bundler keeps only the files it uses. */
    fun runtime(spec: KernelSpec.KotlinPackage) {
        plan(spec)
        val directory = File(spec.destination.paths.single())
        val manifest = directory.resolve("package.json").takeIf(File::isFile) ?: return
        val metadata = Json.parseToJsonElement(manifest.readText()).jsonObject
        val main = directory.resolve(metadata["main"]?.jsonPrimitive?.content ?: return)
        val barrels = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".mjs") && directory.resolve(it.name.removeSuffix(".mjs")).isDirectory }
            .sortedBy { it.name }
        if (main !in barrels) return
        val eager = linkedSetOf<String>()
        fun initializers(barrel: File, seen: MutableSet<File> = mutableSetOf()) {
            if (!seen.add(barrel)) return
            sideEffectImport.findAll(barrel.readText()).map { it.groupValues[1] }.forEach { path ->
                val target = directory.resolve(path)
                if (target in barrels) initializers(target, seen) else eager.add(path)
            }
        }
        initializers(main)
        directory.resolve(RuntimeEntry).writeText((eager.map { "import './$it';" } + barrels.map { "export * from './${it.name}';" }).joinToString("\n", postfix = "\n"))
        val prelude = linkedSetOf<String>()
        val declarations = directory.walkTopDown().filter { it.isFile && it.name.endsWith(".export.d.mts") }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .map { file ->
                val lines = file.readText().lines()
                val start = lines.indexOfFirst { !it.startsWith("type ") && !it.startsWith("declare ") }.takeIf { it >= 0 } ?: lines.size
                prelude.addAll(lines.take(start))
                lines.drop(start).joinToString("\n").trim()
            }.filter(String::isNotEmpty).toList()
        directory.resolve(RuntimeTypes).writeText((prelude + declarations).joinToString("\n", postfix = "\n"))
        val runtime = JsonObject(metadata + mapOf("main" to JsonPrimitive(RuntimeEntry), "types" to JsonPrimitive(RuntimeTypes)))
        manifest.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), runtime) + "\n")
    }

    fun receipt(spec: KernelSpec.KotlinPackage): String {
        plan(spec)
        return "dependency=" + File(spec.dependencies.paths.single()).relativeTo(File(spec.destination.paths.single())).invariantSeparatorsPath + "\n"
    }
}
