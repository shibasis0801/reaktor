package dev.shibasis.reaktor.code

import dev.shibasis.reaktor.graph.core.*
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest

data class CodeOccurrence(val symbol: String, val path: String, val digest: String, val span: CodeSpan,
    val roles: Int, val text: String) {
    val definition: Boolean get() = roles and 1 != 0
    val identity: String get() = if (symbol.startsWith("local ")) "$path::$symbol" else symbol
}

data class CodeSemanticResult(val index: CodeSemanticIndex, val symbol: String,
    val occurrences: List<CodeOccurrence>, val total: Int, val omissions: List<String>)

data class CodeInvestigation(val result: CodeSemanticResult, val selected: CodeOccurrence,
    val files: Map<String, Int>, val definitions: Int, val references: Int)

data class CodeSymbolSelection(val candidates: List<CodeOccurrence>, val total: Int)

class CodeSemanticIndex private constructor(val digest: String, val provider: String, val compiler: String, val target: String,
    val inputs: Map<String, String>, val binaryInputs: Map<String, String>, val documents: Map<String, CodeDocument>,
    val occurrences: List<CodeOccurrence>, val graph: GraphShape) {
    private val bySymbol = graph.wires.filter { it.type in setOf("code.definition", "code.reference") }.groupBy { it.provider }
    private val relationships = graph.wires.filter { it.type == "code.symbol-reference" }.groupBy { it.consumer }
    private val byWire = occurrences.associateBy(::occurrenceId)
    private val symbolNodes = graph.nodes.filter { it.type == "CodeSymbol" }.associateBy { it.label }
    private val byName = bySymbol.values.flatten().map { byWire.getValue(it.definitionId) }.groupBy { it.text }
    private val byPath = occurrences.groupBy { it.path }
    private val starts = documents.mapValues { (_, document) -> buildList {
        add(0); document.text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    } }

    internal fun validateBinaryInputs() {
        binaryInputs.forEach { (name, expected) ->
            val path = Path.of(name)
            require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path && Files.isRegularFile(path)
                && Files.size(path) <= 536870912) { "Use canonical, bounded binary inputs: $name" }
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == expected) { "Stale semantic evidence: binary input changed; re-index this target: $name" }
        }
    }

    fun snippet(occurrence: CodeOccurrence): String {
        val text = documents.getValue(occurrence.path).text
        val lines = starts.getValue(occurrence.path)
        val start = lines[occurrence.span.start.line]
        val end = if (occurrence.span.start.line + 1 < lines.size) lines[occurrence.span.start.line + 1] - 1 else text.length
        val from = (start + occurrence.span.start.column - 100).coerceAtLeast(start)
        return text.substring(from, minOf(from + 600, end)).removeSuffix("\r")
    }

    fun query(path: String, at: CodePosition, kind: String, limit: Int = 40): CodeSemanticResult {
        require(limit in 1..128 && kind in setOf("definition", "references", "impact", "graph"))
        val selected = selected(path, at)
        val all = related(selected)
        val matches = when (kind) {
            "definition" -> all.filter { it.definition }
            "references" -> all.filterNot { it.definition }
            else -> all
        }
        return CodeSemanticResult(this, selected.identity, matches.take(limit), matches.size, omissions(matches.size, limit) + buildList {
            if (kind == "definition" && matches.isEmpty()) add("No definition is recorded in this index; the symbol may be external.")
        })
    }

    fun investigate(path: String, at: CodePosition, limit: Int = 40): CodeInvestigation {
        require(limit in 1..128)
        return investigate(selected(path, at), limit)
    }

    fun symbols(name: String, path: String? = null, limit: Int = 40): CodeSymbolSelection {
        require(name.isNotBlank() && name.length <= 512 && '\n' !in name && '\r' !in name)
        require(limit in 1..128)
        require(path == null || path in documents) { "File is outside this indexed target: $path" }
        val matches = byName[name].orEmpty().filter { path == null || it.path == path }
            .groupBy { it.identity }.values.map { group ->
                group.minWith(compareBy({ !it.definition }, { it.path }, { it.span.start }, { it.span.end }))
            }.sortedWith(compareBy({ it.path }, { it.span.start }, { it.identity }))
        return CodeSymbolSelection(matches.take(limit), matches.size)
    }

    fun investigate(symbol: String, limit: Int = 40): CodeInvestigation {
        require(limit in 1..128)
        val node = requireNotNull(symbolNodes[symbol]) { "Unknown symbol in this index" }
        val selected = bySymbol[node.id].orEmpty().map { byWire.getValue(it.definitionId) }
            .minWithOrNull(compareBy({ !it.definition }, { it.path }, { it.span.start }, { it.span.end }))
        return investigate(requireNotNull(selected) { "No occurrence is recorded for this symbol" }, limit)
    }

    private fun investigate(selected: CodeOccurrence, limit: Int): CodeInvestigation {
        val all = related(selected)
        val retained = (listOf(selected) + all.filter { it.definition } + all.filterNot { it.definition }).distinct().take(limit)
        val result = CodeSemanticResult(this, selected.identity, retained, all.size, omissions(all.size, limit))
        return CodeInvestigation(result, selected, all.groupingBy { it.path }.eachCount().toSortedMap(),
            all.count { it.definition }, all.count { !it.definition })
    }

    fun sourceGraph(result: CodeSemanticResult): GraphShape {
        require(result.index === this)
        val occurrenceIds = result.occurrences.map(::occurrenceId).toSet()
        val selectedWires = result.occurrences.map { symbolNodes.getValue(it.identity).id }.distinct()
            .flatMap { bySymbol[it].orEmpty() }.filter { it.definitionId in occurrenceIds }
        val nodeIds = selectedWires.flatMap { listOf(it.consumer, it.provider) }.toSet()
        val wires = selectedWires + nodeIds.flatMap { relationships[it].orEmpty() }.filter { it.provider in nodeIds }
        return graph.copy(nodes = graph.nodes.filter { it.id in nodeIds }.map { node -> node.copy(ports = node.ports.map { port ->
            val count = wires.count { it.type == port.type && if (port.provides) it.provider == node.id && it.providerPort == port.key else it.consumer == node.id && it.consumerPort == port.key }
            port.copy(wiring = if (count == 0) PortWiring.Unused else PortWiring.Connected, connections = count)
        }) }, wires = wires)
    }

    private fun related(selected: CodeOccurrence): List<CodeOccurrence> {
        val initial = symbolNodes.getValue(selected.identity).id
        val symbols = linkedSetOf(initial)
        val pending = ArrayDeque<String>().apply { add(initial) }
        while (pending.isNotEmpty()) relationships[pending.removeFirst()].orEmpty().forEach { wire ->
            if (symbols.add(wire.provider)) { require(symbols.size <= 256) { "Symbol relationship expansion is too large" }; pending.add(wire.provider) }
        }
        return symbols.flatMap { bySymbol[it].orEmpty() }.map { byWire.getValue(it.definitionId) }.distinct()
            .sortedWith(compareBy({ it.path }, { it.span.start }, { it.span.end }))
    }

    private fun omissions(total: Int, limit: Int) = buildList {
        add("Coverage is the indexed target only; external packages, other targets and runtime dispatch are not inferred.")
        if (provider.startsWith("kotlin-analysis-api/")) add("Kotlin coverage is named source declarations and explicit name references in the declared common/JVM modules; operator calls, inferred calls/types, compiler plugins and other platforms are not indexed.")
        if (total > limit) add("${total - limit} occurrences omitted by the result limit.")
    }

    internal fun localRename(path: String, at: CodePosition, newName: String): List<CodeOccurrence> {
        require(provider == "scip-typescript/0.4.0") { "Local rename is qualified only for scip-typescript/0.4.0" }
        require(compiler == "typescript/5.9.3") { "Local rename requires the qualified TypeScript compilation" }
        require(Regex("[A-Za-z_$][A-Za-z0-9_$]*").matches(newName) && newName !in reserved) { "Use an ordinary, non-keyword TypeScript identifier" }
        val selected = selected(path, at)
        require(selected.symbol.startsWith("local ")) { "This recipe renames file-local symbols; project-wide rename needs a multi-file recipe" }
        val matches = bySymbol.getValue(symbolNodes.getValue(selected.identity).id).map { byWire.getValue(it.definitionId) }.distinctBy { it.span }
        require(matches.size in 2..1000 && matches.count { it.definition } == 1 && matches.all { it.path == path && it.roles and 16 == 0 && it.text == selected.text }) {
            "Expected one local definition and uniformly spelled, non-generated references" }
        require(newName != selected.text)
        require(matches.none { match -> byPath[path].orEmpty().any { it.identity != selected.identity && it.span == match.span } }) { "A rename would also change another symbol at the same range; use a language-aware project recipe" }
        require(byPath[path].orEmpty().none { it.identity != selected.identity && it.text == newName }) { "The new name collides with another indexed symbol in this file" }
        val ordered = matches.sortedBy { it.span.start }
        require(ordered.zipWithNext().all { (left, right) -> left.span.end <= right.span.start }) { "Overlapping semantic edits are not supported" }
        return matches
    }

    private fun selected(path: String, at: CodePosition): CodeOccurrence {
        offset(path, at)
        val selected = byPath[path].orEmpty().filter { at >= it.span.start && at < it.span.end }.distinctBy { it.identity }
        require(selected.size == 1) { "Position must select exactly one indexed symbol; found ${selected.size}" }
        return selected.single()
    }

    internal fun offset(path: String, position: CodePosition): Int {
        val document = requireNotNull(documents[path]) { "File is outside this indexed target: $path" }
        val lines = starts.getValue(path)
        require(position.line in lines.indices && position.column >= 0)
        val start = lines[position.line]
        val end = if (position.line + 1 < lines.size) lines[position.line + 1] - 1 else document.text.length
        val contentEnd = if (end > start && document.text[end - 1] == '\r') end - 1 else end
        require(position.column <= contentEnd - start) { "Position is outside the source line" }
        val offset = start + position.column
        require(offset == 0 || offset == document.text.length || !(document.text[offset - 1].isHighSurrogate() && document.text[offset].isLowSurrogate())) { "Position splits a UTF-16 surrogate pair" }
        return offset
    }

    companion object {
        private fun occurrenceId(occurrence: CodeOccurrence) = listOf("code-occurrence", occurrence.path, occurrence.span.start.toString(),
            occurrence.span.end.toString(), occurrence.identity, occurrence.roles.toString()).joinToString("\u0000")

        private val reserved = ("break case catch class const continue debugger default delete do else enum export extends false finally for function if import in instanceof new null return super switch this throw true try typeof var void while with implements interface let package private protected public static yield any boolean constructor declare get module require number set string symbol type from of as async await abstract readonly never unknown namespace keyof infer is asserts satisfies using").split(' ').toSet()

        internal fun parse(root: Path, digest: String, source: String, read: (String) -> CodeDocument): CodeSemanticIndex {
            val artifact = Json.parseToJsonElement(source).jsonObject
            require(artifact["format"]?.jsonPrimitive?.content == "reaktor-scip-v1") { "Use the Code-owned SCIP helper to bind the index to source identities" }
            val target = artifact.getValue("target").jsonPrimitive.content
            require(target.isNotBlank() && target.length <= 4096)
            val compiler = artifact.getValue("compiler").jsonPrimitive.content
            require(compiler.isNotBlank() && compiler.length <= 256)
            val inputs = artifact.getValue("inputs").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
            require(inputs.size in 1..4096 && target in inputs && inputs.values.all { Regex("[0-9a-f]{64}").matches(it) })
            val binaryInputs = artifact["binaryInputs"]?.jsonObject.orEmpty().mapValues { (_, value) -> value.jsonPrimitive.content }
            require(binaryInputs.size <= 256 && binaryInputs.values.all { Regex("[0-9a-f]{64}").matches(it) })
            val index = artifact.getValue("index").jsonObject
            val metadata = index.getValue("metadata").jsonObject
            require(Path.of(URI(metadata.getValue("project_root").jsonPrimitive.content)).toRealPath() == root) { "SCIP index belongs to another workspace" }
            val tool = metadata.getValue("tool_info").jsonObject
            val provider = tool.getValue("name").jsonPrimitive.content + "/" + tool.getValue("version").jsonPrimitive.content
            require(provider.length <= 256 && !provider.endsWith("/"))
            if (provider.startsWith("kotlin-analysis-api/")) require(binaryInputs.isNotEmpty()) { "Kotlin semantic evidence must bind its binary compilation inputs" }
            val rawDocuments = index.getValue("documents").jsonArray
            require(rawDocuments.size in 1..4096)
            val documents = linkedMapOf<String, CodeDocument>()
            val occurrences = mutableListOf<CodeOccurrence>()
            val relationships = mutableMapOf<String, MutableSet<String>>()
            rawDocuments.forEach { raw ->
                val document = raw.jsonObject
                val path = document.getValue("relative_path").jsonPrimitive.content
                require(path.split('/').none { it.isEmpty() || it == "." || it == ".." } && '\\' !in path && path !in documents)
                val current = read(path)
                require(inputs[path] == current.digest) { "SCIP source is unbound or stale: $path" }
                require(document["position_encoding"]?.jsonPrimitive?.intOrNull == 2) { "SCIP import requires explicit UTF-16 position encoding" }
                document["text"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let { require(it == current.text) }
                documents[path] = current
                val lines = current.text.split('\n')
                fun position(line: Int, column: Int): CodePosition {
                    require(line in lines.indices && column in 0..lines[line].removeSuffix("\r").length)
                    val text = lines[line]
                    require(column == 0 || column == text.length || !(text[column - 1].isHighSurrogate() && text[column].isLowSurrogate()))
                    return CodePosition(line, column)
                }
                fun range(occurrence: JsonObject): CodeSpan {
                    val single = occurrence["single_line_range"] as? JsonObject
                    val multi = occurrence["multi_line_range"] as? JsonObject
                    fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.intOrNull ?: 0
                    val values = when {
                        single != null -> listOf(single.number("line"), single.number("start_character"), single.number("line"), single.number("end_character"))
                        multi != null -> listOf(multi.number("start_line"), multi.number("start_character"), multi.number("end_line"), multi.number("end_character"))
                        else -> occurrence.getValue("range").jsonArray.map { it.jsonPrimitive.int }.let {
                            require(it.size == 3 || it.size == 4)
                            if (it.size == 3) listOf(it[0], it[1], it[0], it[2]) else it
                        }
                    }
                    return CodeSpan(position(values[0], values[1]), position(values[2], values[3])).also { require(it.start <= it.end) }
                }
                document["occurrences"]?.jsonArray.orEmpty().forEach { rawOccurrence ->
                    val item = rawOccurrence.jsonObject
                    val symbol = item["symbol"]?.jsonPrimitive?.content.orEmpty()
                    if (symbol.isNotEmpty()) {
                        require(symbol.length <= 4096 && occurrences.size < 200000)
                        val span = range(item)
                        val text = if (span.singleLine) lines[span.start.line].substring(span.start.column, span.end.column) else ""
                        occurrences += CodeOccurrence(symbol, path, current.digest, span, item["symbol_roles"]?.jsonPrimitive?.intOrNull ?: 0, text)
                    }
                }
                document["symbols"]?.jsonArray.orEmpty().forEach { rawSymbol ->
                    val item = rawSymbol.jsonObject
                    val symbol = item.getValue("symbol").jsonPrimitive.content
                    fun identity(value: String) = if (value.startsWith("local ")) "$path::$value" else value
                    item["relationships"]?.jsonArray.orEmpty().forEach { rawRelation ->
                        val relation = rawRelation.jsonObject
                        if (relation["is_reference"]?.jsonPrimitive?.booleanOrNull == true) {
                            val other = relation.getValue("symbol").jsonPrimitive.content
                            relationships.getOrPut(identity(symbol)) { linkedSetOf() }.add(identity(other))
                            relationships.getOrPut(identity(other)) { linkedSetOf() }.add(identity(symbol))
                        }
                    }
                }
            }
            val scope = "code:$root:$target"
            val symbols = (occurrences.map { it.identity } + relationships.keys + relationships.values.flatten()).distinct().sorted()
            fun symbolId(symbol: String) = "$scope/symbol:$symbol"
            val wires = occurrences.map { occurrence ->
                val role = if (occurrence.definition) "definition" else "reference"
                WireShape("$scope/file:${occurrence.path}", role, symbolId(occurrence.identity), role, "code.$role", occurrenceId(occurrence))
            }.distinct() +
                relationships.flatMap { (symbol, others) -> others.sorted().map { other -> WireShape(symbolId(symbol), "references", symbolId(other), "definition",
                    "code.symbol-reference", "$scope/relationship:$symbol:$other") } }
            val consumers = wires.groupBy { it.consumer }
            val providers = wires.groupBy { it.provider }
            fun ports(id: String) = consumers[id].orEmpty().groupingBy { it.consumerPort to it.type }.eachCount().map { (key, count) ->
                PortShape(key.first, key.second, false, PortWiring.Connected, count, definitionId = "$id/consume:${key.first}:${key.second}")
            } + providers[id].orEmpty().groupingBy { it.providerPort to it.type }.eachCount().map { (key, count) ->
                PortShape(key.first, key.second, true, PortWiring.Connected, count, definitionId = "$id/provide:${key.first}:${key.second}")
            }
            val nodes = documents.map { (path, document) -> NodeShape("$scope/file:$path", scope, path, "CodeDocument", NodeKind.Node,
                ports = ports("$scope/file:$path"),
                attributes = mapOf("sourceDigest" to document.digest, "provider" to provider, "indexDigest" to digest), definitionId = "$scope/file:$path") } +
                symbols.map { symbol -> NodeShape(symbolId(symbol), scope, symbol, "CodeSymbol", NodeKind.Node,
                    ports = ports(symbolId(symbol)),
                    attributes = mapOf("indexDigest" to digest, "provider" to provider), definitionId = symbolId(symbol)) }
            val graph = GraphShape(listOf(ScopeShape(scope, target, active = false, definitionId = scope)), nodes, wires, emptyList(), digest)
            return CodeSemanticIndex(digest, provider, compiler, target, inputs, binaryInputs, documents, occurrences, graph)
        }
    }
}
