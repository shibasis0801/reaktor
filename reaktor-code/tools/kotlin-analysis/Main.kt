@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
@file:Suppress("DEPRECATION")

package dev.shibasis.reaktor.code.kotlinanalysis

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
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
    if (args.firstOrNull() == "serve") {
        require(args.size == 2) { "Usage: run serve WORKSPACE" }
        return serve(Path.of(args[1]).toRealPath())
    }
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

private class Warm(val digest: String, val disposable: com.intellij.openapi.Disposable, val files: Map<String, KtFile>, val stamps: Map<Path, Pair<Long, Long>>, val skipped: List<String>) {
    val paths: Map<KtFile, String> = files.entries.associate { (path, file) -> file to path }
    fun fresh() = stamps.all { (path, stamp) -> runCatching { Files.getLastModifiedTime(path).toMillis() to Files.size(path) }.getOrNull() == stamp }
    fun close() = Disposer.dispose(disposable)
}

private fun serve(root: Path) {
    val protocol = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8)
    System.setOut(System.err)
    var warm: Warm? = null
    val input = System.`in`.bufferedReader(Charsets.UTF_8)
    try {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val started = System.nanoTime()
            val request = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            val reply = if (request == null) buildJsonObject { put("error", "Unreadable request") } else runCatching {
                val digest = MessageDigest.getInstance("SHA-256").digest((request.getValue("modules").toString() + "\u0000" + request.string("jdkHome")).toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val reused = warm?.takeIf { it.digest == digest && it.fresh() }
                val session = reused ?: run {
                    warm?.close()
                    warm = null
                    open(root, request, digest).also { warm = it }
                }
                JsonObject(resolve(session, request) + ("warm" to JsonPrimitive(reused != null)))
            }.getOrElse { failure -> buildJsonObject { put("error", (failure.message ?: failure.toString()).take(2000)) } }
            protocol.println(JsonObject(reply + mapOf("id" to (request?.get("id") ?: JsonNull), "millis" to JsonPrimitive((System.nanoTime() - started) / 1_000_000))))
        }
    } finally {
        warm?.close()
        disposeGlobalStandaloneApplicationServices()
    }
}

private fun open(root: Path, request: JsonObject, digest: String): Warm {
    val jdk = Path.of(request.string("jdkHome")).toRealPath()
    val jvm = JvmPlatforms.jvmPlatformByTargetVersion(JvmTarget.JVM_25)
    val settings = LanguageVersionSettingsImpl(LanguageVersion.KOTLIN_2_4, requireNotNull(ApiVersion.parse("2.4")),
        specificFeatures = mapOf(LanguageFeature.MultiPlatformProjects to LanguageFeature.State.ENABLED))
    val modules = request.getValue("modules").jsonArray.map { it.jsonObject }
    require(modules.size in 1..256) { "A session holds 1 to 256 modules" }
    val stamps = linkedMapOf<Path, Pair<Long, Long>>()
    val skipped = mutableListOf<String>()
    val disposable = Disposer.newDisposable("Reaktor Kotlin resolver")
    try {
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = jvm
                val sdk = addModule(buildKtSdkModule {
                    libraryName = "jdk"; platform = jvm
                    addBinaryRootsFromJdkHome(jdk, false)
                })
                val libraries = HashMap<Pair<String, Boolean>, KaModule>()
                val built = linkedMapOf<String, KaSourceModule>()
                modules.forEach { spec ->
                    val name = spec.string("name")
                    require(name.isNotBlank() && name !in built) { "Each module needs its own name: $name" }
                    val common = spec.string("platform") == "common"
                    val target = if (common) CommonPlatforms.defaultCommonPlatform else jvm
                    val sources = spec.strings("sources").mapNotNull { relative ->
                        root.resolve(relative).normalize().takeIf { it.startsWith(root) && it.toString().endsWith(".kt") && Files.isRegularFile(it) } ?: run { skipped += relative; null }
                    }
                    sources.forEach { stamps[it] = Files.getLastModifiedTime(it).toMillis() to Files.size(it) }
                    val classpath = spec.strings("classpath").map { Path.of(it) }.filter { Files.exists(it) }
                    built[name] = addModule(buildKtSourceModule {
                        moduleName = name; platform = target; languageVersionSettings = settings
                        addSourceRoots(sources)
                        classpath.forEach { path ->
                            addRegularDependency(libraries.getOrPut(path.toString() to common) {
                                addModule(buildKtLibraryModule { libraryName = path.toString(); platform = target; addBinaryRoot(path) })
                            })
                        }
                        if (!common) addRegularDependency(sdk)
                        spec.strings("dependsOn").mapNotNull { built[it] }.forEach(::addDependsOnDependency)
                        spec.strings("dependencies").mapNotNull { built[it] }.forEach(::addRegularDependency)
                        spec.strings("friends").mapNotNull { built[it] }.forEach(::addFriendDependency)
                    })
                }
            }
        }
        val files = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>().mapNotNull { file ->
            val path = Path.of(file.virtualFile.path).normalize()
            if (path.startsWith(root)) root.relativize(path).toString().replace('\\', '/') to file else null
        }.toMap()
        return Warm(digest, disposable, files, stamps, skipped)
    } catch (failure: Throwable) {
        Disposer.dispose(disposable)
        throw failure
    }
}

private fun resolve(warm: Warm, request: JsonObject): JsonObject {
    val starts = HashMap<KtFile, IntArray>()
    fun lines(file: KtFile) = starts.getOrPut(file) { (listOf(0) + file.text.indices.filter { file.text[it] == '\n' }.map { it + 1 }).toIntArray() }
    fun offset(file: KtFile, line: Int, column: Int): Int? = lines(file).getOrNull(line)?.plus(column)?.takeIf { it <= file.textLength }
    fun place(element: PsiElement): String? {
        val file = element.containingFile as? KtFile ?: return null
        val path = warm.paths[file] ?: return null
        val all = lines(file)
        val offset = element.textOffset
        val line = all.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        return "$path:$line:${offset - all[line]}"
    }
    fun KaSession.identity(symbol: KaSymbol): String? {
        val real = (symbol as? KaCallableSymbol)?.fakeOverrideOriginal ?: symbol
        val declared = if (real is KaConstructorSymbol) real.containingDeclaration ?: real else real
        (declared.psi as? KtNamedDeclaration)?.nameIdentifier?.let(::place)?.let { return it }
        return (declared as? KaCallableSymbol)?.callableId?.let { "external:$it" } ?: (declared as? KaClassLikeSymbol)?.classId?.let { "external:$it" }
    }
    fun KaSession.related(symbol: KaDeclarationSymbol): List<String> =
        ((symbol as? KaCallableSymbol)?.allOverriddenSymbols.orEmpty().toList() + symbol.getExpectsForActual()).mapNotNull { identity(it) }
    fun declarationAt(file: KtFile, offset: Int): KtNamedDeclaration? =
        PsiTreeUtil.getParentOfType(file.findElementAt(offset), KtNamedDeclaration::class.java, false)?.takeIf { it.nameIdentifier?.textRange?.containsOffset(offset) == true }

    val asked = request.getValue("declaration").jsonObject
    val targetFile = requireNotNull(warm.files[asked.string("path")]) { "The declaration's file is not in this session: ${asked.string("path")}" }
    val targetOffset = requireNotNull(offset(targetFile, asked.getValue("line").jsonPrimitive.int, asked.getValue("column").jsonPrimitive.int)) { "No such position" }
    val declaration = requireNotNull(declarationAt(targetFile, targetOffset)) { "No declaration name at ${asked.string("path")}:${asked.getValue("line")}:${asked.getValue("column")}" }
    val target = requireNotNull(place(requireNotNull(declaration.nameIdentifier)))
    val edges = HashMap<String, MutableSet<String>>()
    fun link(a: String, b: String) { edges.getOrPut(a) { linkedSetOf() } += b; edges.getOrPut(b) { linkedSetOf() } += a }
    analyze(declaration) { related(declaration.symbol).forEach { link(target, it) } }

    class Candidate(val path: String, val line: Int, val column: Int, val file: KtFile?, val offset: Int?)
    val candidates = request["candidates"]?.jsonArray.orEmpty().map { it.jsonObject }.map { candidate ->
        val path = candidate.string("path")
        val line = candidate.getValue("line").jsonPrimitive.int
        val column = candidate.getValue("column").jsonPrimitive.int
        val file = warm.files[path]
        Candidate(path, line, column, file, file?.let { offset(it, line, column) })
    }
    val declared = HashMap<Candidate, String>()
    val resolved = HashMap<Candidate, List<String>>()
    candidates.forEach { candidate ->
        val file = candidate.file ?: return@forEach
        val offset = candidate.offset ?: return@forEach
        val named = declarationAt(file, offset)
        if (named != null) {
            val identity = place(requireNotNull(named.nameIdentifier)) ?: return@forEach
            declared[candidate] = identity
            runCatching { analyze(named) { related(named.symbol).forEach { link(identity, it) } } }
            return@forEach
        }
        val name = PsiTreeUtil.getParentOfType(file.findElementAt(offset), KtSimpleNameExpression::class.java, false) ?: return@forEach
        resolved[candidate] = runCatching { analyze(name) { name.mainReference.resolveToSymbols().mapNotNull { identity(it) }.distinct() } }.getOrDefault(emptyList())
    }
    val family = linkedSetOf(target)
    val pending = ArrayDeque(listOf(target))
    while (pending.isNotEmpty()) edges[pending.removeFirst()].orEmpty().forEach { if (family.add(it)) pending.addLast(it) }
    return buildJsonObject {
        put("target", target)
        putJsonArray("family") { family.filterNot { it.startsWith("external:") }.forEach { add(it) } }
        putJsonArray("external") { family.filter { it.startsWith("external:") }.forEach { add(it) } }
        putJsonArray("skipped") { warm.skipped.take(20).forEach { add(it) } }
        putJsonArray("decisions") {
            candidates.forEach { candidate ->
                addJsonObject {
                    put("path", candidate.path); put("line", candidate.line); put("column", candidate.column)
                    val identities = declared[candidate]?.let(::listOf) ?: resolved[candidate]
                    put("verdict", when {
                        candidate.file == null -> "outside"
                        identities == null -> "unresolved"
                        identities.isEmpty() -> "unresolved"
                        identities.all { it in family } -> "target"
                        identities.any { it in family } -> "mixed"
                        else -> "other"
                    })
                    identities?.takeIf { it.isNotEmpty() }?.let { found -> putJsonArray("resolved") { found.take(4).forEach { add(it) } } }
                }
            }
        }
    }
}
