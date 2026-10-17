@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
@file:Suppress("DEPRECATION")

package dev.shibasis.reaktor.code.kotlinanalysis

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val version = "2.5.0-Beta2-59"
private fun hash(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(65536)
        while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.strings(key: String) = get(key)?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
private fun JsonObject.keysOnly(vararg keys: String) = require(this.keys.all { it in keys }) { "Unknown target option: ${this.keys - keys.toSet()}" }

fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: run WORKSPACE RELATIVE_TARGET OUTPUT_ARTIFACT" }
    val root = Path.of(args[0]).toRealPath()
    fun local(name: String): Path {
        val path = root.resolve(name).normalize()
        require(name.isNotEmpty() && !Path.of(name).isAbsolute && name.split('/').none {
            it in setOf("", ".", "..", ".git", ".gradle", "build", "node_modules") || it.startsWith(".env")
        } && '\\' !in name && path.startsWith(root) && path.toRealPath() == path && Files.isRegularFile(path)) { "Use canonical workspace files: $name" }
        require(Files.size(path) <= 524288) { "Source input exceeds Code scope: $name" }
        return path
    }
    val target = args[1]
    val manifestPath = local(target)
    val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
    manifest.keysOnly("format", "compiler", "languageVersion", "apiVersion", "jdkHome", "jvmTarget", "modules")
    require(manifest.string("format") == "reaktor-kotlin-target-v1")
    require(manifest.string("compiler") == "kotlin/2.4.21") { "This producer is qualified for explicit Kotlin 2.4.21 inputs" }
    require(manifest.string("jvmTarget") == "25") { "This producer is qualified for JVM 25" }
    val language = requireNotNull(LanguageVersion.fromVersionString(manifest.string("languageVersion")))
    val api = requireNotNull(ApiVersion.parse(manifest.string("apiVersion")))
    require(language == LanguageVersion.KOTLIN_2_4 && api.versionString == "2.4") { "Use qualified language/API 2.4 settings" }
    val settings = LanguageVersionSettingsImpl(language, api, specificFeatures = mapOf(LanguageFeature.MultiPlatformProjects to LanguageFeature.State.ENABLED))
    val jvmPlatform = JvmPlatforms.jvmPlatformByTargetVersion(JvmTarget.JVM_25)
    val jdk = Path.of(manifest.string("jdkHome")).toRealPath()
    val binaryInputs = sortedMapOf<String, String>()
    fun binary(name: String): Path {
        val path = Path.of(name).toAbsolutePath().normalize()
        require(path.toRealPath() == path && Files.isRegularFile(path) && Files.size(path) <= 536870912) { "Use canonical binary inputs: $name" }
        require(binaryInputs.size < 256)
        binaryInputs[path.toString()] = hash(path)
        return path
    }
    binary(jdk.resolve("release").toString())
    binary(jdk.resolve("lib/modules").toString())
    require(Files.readString(jdk.resolve("release")).contains("JAVA_VERSION=\"25")) { "Use an explicit JDK 25" }
    val inputs = sortedMapOf(target to hash(manifestPath))
    val modules = manifest.getValue("modules").jsonArray.map { it.jsonObject }
    require(modules.size in 1..64)
    val sources = linkedMapOf<String, String>()
    modules.forEach { module ->
        module.keysOnly("name", "platform", "sources", "classpath", "dependsOn", "dependencies")
        require(module.string("platform") in setOf("common", "jvm")) { "Only common and JVM source modules are qualified" }
        require(module.strings("sources").isNotEmpty())
        module.strings("sources").forEach { name ->
            require(name.endsWith(".kt") && name !in sources && sources.size < 4095) { "Each Kotlin source must belong to exactly one module: $name" }
            sources[name] = module.string("name")
            inputs[name] = hash(local(name))
        }
        module.strings("classpath").forEach(::binary)
    }
    val disposable = Disposer.newDisposable("Reaktor Kotlin semantic producer")
    try {
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = jvmPlatform
                val sdk = addModule(buildKtSdkModule {
                    libraryName = "jdk25"; platform = jvmPlatform
                    addBinaryRootsFromJdkHome(jdk, false)
                })
                val built = linkedMapOf<String, KaSourceModule>()
                modules.forEach { spec ->
                    val name = spec.string("name")
                    require(name.isNotBlank() && name.length <= 128 && name !in built)
                    val targetPlatform = if (spec.string("platform") == "common") CommonPlatforms.defaultCommonPlatform else jvmPlatform
                    val libraries = spec.strings("classpath").map { path -> addModule(buildKtLibraryModule {
                        libraryName = path; platform = targetPlatform; addBinaryRoot(Path.of(path))
                    }) }
                    built[name] = addModule(buildKtSourceModule {
                        moduleName = name; platform = targetPlatform; languageVersionSettings = settings
                        addSourceRoots(spec.strings("sources").map(::local))
                        libraries.forEach(::addRegularDependency)
                        if (spec.string("platform") == "jvm") addRegularDependency(sdk)
                        spec.strings("dependsOn").forEach { addDependsOnDependency(requireNotNull(built[it]) { "Declare dependsOn modules first: $it" }) }
                        spec.strings("dependencies").forEach { addRegularDependency(requireNotNull(built[it]) { "Declare dependency modules first: $it" }) }
                    })
                }
            }
        }
        fun sourcePath(element: PsiElement): String? {
            val file = element.containingFile?.virtualFile ?: return null
            val path = Path.of(file.path).normalize()
            return if (path.startsWith(root)) root.relativize(path).toString().replace('\\', '/').takeIf { it in sources } else null
        }
        fun identity(symbol: KaSymbol): String? {
            val psi = symbol.psi ?: return null
            val path = sourcePath(psi) ?: return null
            val declaration = psi as? KtNamedDeclaration ?: return null
            val anchor = declaration.nameIdentifier ?: return null
            return "kotlin ${sources.getValue(path)}/$path#${anchor.textOffset}"
        }
        val documents = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>().sortedBy { sourcePath(it) }.map { file ->
            val path = requireNotNull(sourcePath(file))
            val original = Files.readString(local(path))
            require(original.replace("\r\n", "\n").replace('\r', '\n') == file.text) { "Unexpected PSI/source normalization: $path" }
            val psiLines = buildList { add(0); file.text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            fun position(offset: Int): Pair<Int, Int> {
                val found = psiLines.binarySearch(offset)
                val line = if (found >= 0) found else -found - 2
                return line to (offset - psiLines[line])
            }
            fun occurrence(element: PsiElement, symbol: String, definition: Boolean): JsonObject {
                val (startLine, startColumn) = position(element.textRange.startOffset)
                val (endLine, endColumn) = position(element.textRange.endOffset)
                require(startLine == endLine)
                return buildJsonObject {
                    put("symbol", symbol); put("symbol_roles", if (definition) 1 else 0)
                    putJsonArray("range") { add(startLine); add(startColumn); add(endColumn) }
                }
            }
            val occurrences = analyze(file) {
                val errors = file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).filter { it.severity.name == "ERROR" }
                require(errors.isEmpty()) { "Unqualified compilation in $path: ${errors.take(12).joinToString { it.defaultMessage }}" }
                buildList {
                    PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java).forEach { declaration ->
                        if (declaration.nameIdentifier != null) identity(declaration.symbol)?.let { add(occurrence(declaration.nameIdentifier!!, it, true)) }
                    }
                    PsiTreeUtil.collectElementsOfType(file, KtNameReferenceExpression::class.java).forEach { reference ->
                        reference.mainReference.resolveToSymbol()?.let(::identity)?.let { add(occurrence(reference, it, false)) }
                    }
                }.distinct()
            }
            buildJsonObject { put("relative_path", path); put("position_encoding", 2); put("occurrences", JsonArray(occurrences)) }
        }
        require(documents.size == sources.size) { "Analysis did not load every declared source" }
        inputs.forEach { (name, expected) -> require(hash(local(name)) == expected) { "Source changed during analysis: $name" } }
        binaryInputs.forEach { (name, expected) -> require(hash(Path.of(name)) == expected) { "Binary changed during analysis: $name" } }
        val artifact = buildJsonObject {
            put("format", "reaktor-scip-v1"); put("target", target)
            put("compiler", "kotlin-analysis/$version (target kotlin/2.4.21, language/api 2.4, JVM 25)")
            put("inputs", JsonObject(inputs.mapValues { JsonPrimitive(it.value) }))
            put("binaryInputs", JsonObject(binaryInputs.mapValues { JsonPrimitive(it.value) }))
            putJsonObject("index") {
                putJsonObject("metadata") {
                    put("project_root", root.toUri().toString())
                    putJsonObject("tool_info") { put("name", "kotlin-analysis-api"); put("version", version) }
                }
                put("documents", JsonArray(documents))
            }
        }.toString()
        require(artifact.toByteArray().size <= 16777216)
        val output = Path.of(args[2]).toAbsolutePath().normalize()
        require(output !in inputs.keys.map { root.resolve(it) } && output.toString() !in binaryInputs)
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, ".kotlin-index-", ".tmp")
        try { Files.writeString(temporary, artifact); Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
        println(buildJsonObject { put("artifact", output.toString()); put("provider", "kotlin-analysis-api/$version"); put("digest", hash(output)); put("documents", documents.size) })
    } finally {
        Disposer.dispose(disposable)
        disposeGlobalStandaloneApplicationServices()
    }
}
