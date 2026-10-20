package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.native.AndroidPrefabConfiguration
import dev.shibasis.dependeasy.native.CMakeLibraries
import dev.shibasis.dependeasy.native.cmake
import dev.shibasis.dependeasy.native.prefabLibrary
import java.io.File

object CMakeBuildKernel {
    fun sourceSet(declaration: NativeContract, directory: File, inputs: List<File>): NativeContract {
        val root = directory.toPath().toAbsolutePath().normalize()
        val android = declaration.platform.startsWith("android/")
        val apple = declaration.settings["CMAKE_SYSTEM_NAME"] in setOf("Darwin", "iOS")
        val sources = inputs.asSequence().map { it.toPath().toAbsolutePath().normalize() }
            .filter { it.startsWith(root) }.filter { path ->
                val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
                when {
                    relative.startsWith("src/commonMain/cpp/") -> path.toFile().extension in setOf("cpp", "c")
                    android && relative.startsWith("src/androidMain/cpp/") -> path.toFile().extension in setOf("cpp", "c")
                    apple && relative.startsWith("src/iosMain/cpp/") -> path.toFile().extension in setOf("cpp", "mm", "m")
                    else -> false
                }
            }.sortedWith(compareBy<java.nio.file.Path> { if (root.relativize(it).startsWith("src/commonMain/cpp")) 0 else 1 }
                .thenBy { it.toString() }).map { it.toString() }.distinct().toList()
        return declaration.copy(compilationSources = sources, libraryKind = if (android) "SHARED" else "STATIC",
            projects = declaration.projects.filter { !it.hostOnly || declaration.platform == "host" },
            executables = declaration.executables.filter { !it.hostOnly || declaration.platform == "host" },
            privateIncludeDirectories = listOf("src/androidMain/cpp", "src/iosMain/cpp").map { directory.resolve(it).absolutePath })
    }

    fun resolve(declaration: NativeContract, dependencyManifests: List<File>,
        dependencyIncludes: Map<String, List<String>>, prefabDirectories: Map<String, String>,
        prefabDeclarations: Map<String, AndroidPrefabConfiguration>): NativeContract {
        var contract = declaration
        val imports = dependencyManifests.sorted().map { manifest ->
            CMakeLibraries.primary(manifest).let { it.copy(includes = dependencyIncludes[it.target].orEmpty()) }
        } + prefabDirectories.map { (name, directory) -> prefabLibrary(java.io.File(directory), contract.settings.getValue("ANDROID_ABI"), prefabDeclarations.getValue(name)) }
        val settings = contract.settings.toMutableMap()
        val libraries = (contract.imports + imports).associateBy { it.target }
        val libraryVariables = prefabDeclarations.values.flatMap { declaration -> declaration.linkVariables.map { it to declaration.target } }
            .groupBy({ it.first }, { libraries.getValue(it.second) }).mapValues { (_, selected) ->
                val referenced = selected.flatMap { it.links }.toSet()
                val ordered = linkedSetOf<String>()
                fun include(target: String) {
                    if (ordered.add(target)) libraries.getValue(target).links.forEach(::include)
                }
                (selected.filter { it.target !in referenced } + selected).forEach { include(it.target) }
                ordered.joinToString(";") { libraries.getValue(it).artifact }
            }
        prefabDeclarations.values.forEach { declaration ->
            val library = libraries.getValue(declaration.target)
            declaration.artifactVariables.forEach { settings[it] = library.artifact }
            declaration.includeVariables.forEach { settings[it] = library.includes.joinToString(";") }
            declaration.linkVariables.forEach { settings[it] = libraryVariables.getValue(it) }
        }
        contract = contract.copy(imports = contract.imports + imports, settings = settings, linkLibraries = (contract.linkLibraries + imports.map { it.target }).distinct())
        return contract
    }

    fun plan(spec: KernelSpec.CMakeBuild): List<ProcessExecution> {
        require(spec.parallelism > 0 && spec.target.isNotBlank())
        val inputs = listOf(spec.source, spec.contract, nativeConfiguration(spec))
        val outputs = listOf(spec.output, requireNotNull(spec.nativeContract), nativeConfiguration(spec))
        return listOf(CMake.configure(spec), CMake.build(spec)).map { arguments ->
            ProcessExecution(spec.source, spec.executable, arguments, spec.environmentKeys, spec.tool, inputs, outputs)
        }
    }

    fun prepare(spec: KernelSpec.CMakeBuild, contract: NativeContract, environment: Map<String, String>): String {
        plan(spec)
        require(environment.keys.sorted() == spec.environmentKeys.sorted()) { "CMake environment does not match its declaration" }
        contract.sources.filter { it.recipe == "webview" }.forEach(NativeSourceKernel::prepare)
        val build = File(spec.output)
        File(requireNotNull(spec.nativeContract)).apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(contract) + "\n") }
        val generatedContract = contract.cmake()
        File(nativeConfiguration(spec)).apply { parentFile.mkdirs(); writeText(generatedContract) }
        val identity = (listOf(File(spec.source).canonicalPath, spec.generator, spec.executable, spec.ninjaExecutable,
            spec.tool.version, environment.toSortedMap().toString(), File(spec.contract).readText(), generatedContract) + spec.arguments).joinToString("\n")
        val configured = build.resolve("configure-arguments.txt")
        if (!configured.isFile || configured.readText() != identity) {
            build.resolve("CMakeCache.txt").delete()
            build.resolve("CMakeFiles").deleteTreeSafely(within = build)
        }
        build.mkdirs()
        CMakeLibraries.request(build)
        return identity
    }

    fun configured(spec: KernelSpec.CMakeBuild, identity: String) {
        File(spec.output).resolve("configure-arguments.txt").writeText(identity)
    }

    fun completed(spec: KernelSpec.CMakeBuild) {
        val target = if (spec.target == CMake.All) BuildModelReader.json.decodeFromString<NativeContract>(
            File(requireNotNull(spec.nativeContract)).readText()).library else spec.target
        CMakeLibraries.record(File(spec.output), target, spec.configuration)
    }

    internal fun nativeConfiguration(spec: KernelSpec.CMakeBuild): String = spec.nativeConfiguration
        ?: File(requireNotNull(spec.nativeContract) { "CMake needs its generated native contract" }).let { File(it.parentFile, it.nameWithoutExtension + ".cmake").path }
}
