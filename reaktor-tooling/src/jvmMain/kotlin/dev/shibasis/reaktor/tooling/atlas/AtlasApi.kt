package dev.shibasis.reaktor.tooling.atlas

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

@Serializable enum class AtlasRelation {
    @SerialName("contains") Contains, @SerialName("references") References, @SerialName("includes") Includes, @SerialName("calls") Calls,
    @SerialName("overrides") Overrides, @SerialName("implements") Implements, @SerialName("typeOf") TypeOf, @SerialName("actualOf") ActualOf,
}

@Serializable enum class AtlasDirection { @SerialName("in") In, @SerialName("out") Out }

@Serializable enum class AtlasStrength {
    @SerialName("inferred") Inferred, @SerialName("syntactic") Syntactic, @SerialName("declared") Declared, @SerialName("resolved") Resolved, @SerialName("observed") Observed,
}

@Serializable enum class AtlasPreset {
    @SerialName("map") Map, @SerialName("investigate") Investigate, @SerialName("impact") Impact, @SerialName("affected-checks") AffectedChecks,
    @SerialName("explain") Explain, @SerialName("wiring") Wiring, @SerialName("runtime") Runtime, @SerialName("routes") Routes
}

@Serializable enum class AtlasPart { @SerialName("declaration") Declaration, @SerialName("outline") Outline, @SerialName("imports") Imports, @SerialName("file") File }

@Serializable
data class AtlasStep(val rel: AtlasRelation, val dir: AtlasDirection = AtlasDirection.Out, @Describe("1 to 4") val depth: Int = 1)

@Serializable
data class AtlasFrom(
    @Describe("A handle from an earlier answer") val id: String? = null,
    @Describe("A declaration name; Type.member or Receiver.function picks one owner") val name: String? = null,
    @Describe("Narrows a name: class, interface, object, enum, function, property or typealias") val kind: String? = null,
    @Describe("A workspace-relative file or folder; it narrows a name or text, and with line and column it is a position") val path: String? = null,
    @Describe("Zero-based line; an answer's at of File.kt:42:5 is line 41") val line: Int? = null,
    @Describe("Zero-based UTF-16 column; an answer's at of File.kt:42:5 is column 4") val column: Int? = null,
    @Describe("A declared build node, build:project:name") val node: String? = null,
    @Describe("A kernel operation id") val operation: String? = null,
    @Describe("A compiler address such as kotlin:pkg/Type#member()@module, as answers print them; or an exact symbol from an index answer, with index") val symbol: String? = null,
    @Describe("Literal text to find in every tracked file: a message, a string, a call such as code.outline, a task name") val text: String? = null,
    @Describe("A position as answers print it: path:line or path:line:column") val at: String? = null,
    @Describe("Files changed in the checkouts, for the impact and affected-checks presets: worktree for what is not committed, or a git ref such as origin/main for everything since it") val changed: String? = null,
)

@Serializable
data class AtlasQuery(
    @Describe("Where the question starts: one of id, name, text, at, path, node, operation or symbol") val from: AtlasFrom = AtlasFrom(),
    @Describe("Relations to follow: references (in) finds uses with the declaration each sits in, then the comments, strings and other files that mention the name; references (out) finds what a declaration or file uses, the functions it calls and the types it names, depth 1 to 4; contains (out) lists declarations; includes (out) walks the build tree. " +
        "From a Kotlin declaration, as the compiler resolves them: calls (in: the call sites and their callers; out: the functions it calls), overrides (in: what overrides it; out: what it overrides), " +
        "implements (in: the types that extend it; out: its supertypes), typeOf (in: declarations of this type; out: its declared type) and actualOf (in: the actuals of an expect; out: the expect of an actual)") val follow: List<AtlasStep> = emptyList(),
    @Describe("A reaktor-scip-v1 artifact; with it names and references resolve through the indexer") val index: String? = null,
    @Describe("1 to 128; counts stay complete when fewer are shown") val limit: Int = 40,
    @Describe("Largest answer in characters, 1000 to 22000; counts stay complete when items are cut") val budget: Int = 12000,
    @Describe("With true, the answer also carries the source of each Kotlin declaration a result sits in, with line numbers, while the budget lasts") val read: Boolean = false,
    @Describe("Lines to show before and after each result, 0 to 10, in any file; overlapping lines show once") val context: Int = 0,
    @Describe("With true, the answer also carries the package and import lines of each Kotlin file a result sits in, as an edit needs them") val imports: Boolean = false,
    @Describe("More atlas_query requests, each with this tool's arguments, answered in this call after this one; lookups that do not wait on each other go together") val queries: List<JsonObject> = emptyList(),
    @Describe("atlas_read requests, each with atlas_read's arguments, answered in this call after the queries") val reads: List<JsonObject> = emptyList(),
    @Describe("The same as from.path, for a request that puts it beside from") val path: String? = null,
    @Describe("A stored question. map: the builds, their modules and platforms, the app graphs and the latest commits, to start a session with. " +
        "investigate: one bounded answer about a declaration (from.name, at, id, or symbol with index): its source, every use with the lines around it and the declaration it sits in, and the files they sit in. " +
        "impact: what a change reaches, from a declaration (from.name, id or at) or files (from.path or from.changed): its uses grouped by module, checked by the compiler for a declaration, and the checks that cover them, each with its reason. " +
        "affected-checks: the checks for files (from.path, a file or folder) or from.changed, each with its reason and latest run. " +
        "explain and wiring, for reaktor-graph code: what a graph, node class, port type or route is, its nodes and ports and what wires to each; which provider each consumer gets, matched as autoWire matches them. " +
        "Name the graph (class or label), node, port type or route in from.name, give from.path for those declared in a file or folder, or neither for the tree of graphs. " +
        "runtime: the app graphs' nodes with the telemetry spans retained for the last hour, joined by definition id; from.name narrows it to a graph or node. " +
        "routes: each declared route and the Maestro flows whose openLink opens it")
    val preset: AtlasPreset? = null,
    @Describe("The least evidence the answer may rest on: inferred, syntactic, declared, resolved or observed. An answer that cannot meet it is refused with PROVIDER_UNAVAILABLE, which names the provider it needs and its state")
    val need: AtlasStrength? = null,
)

@Serializable
data class AtlasItem(val id: String, val kind: String, val name: String? = null, val signature: String? = null, val at: String? = null,
    val from: String? = null, val snippet: String? = null, @SerialName("in") val within: String? = null, val context: AtlasContext? = null,
    @SerialName("by") val caller: String? = null, @SerialName("rel") val relation: String? = null, val symbol: String? = null,
    val strength: AtlasStrength? = null, val uses: Int? = null)

@Serializable
data class AtlasContext(val first: Int, val lines: List<String>)

@Serializable
data class AtlasCounts(val total: Int, val shown: Int)

@Serializable
data class AtlasEvidence(val strength: AtlasStrength, val producers: List<String>, val coverage: String? = null)

@Serializable
data class AtlasNext(val op: String, val args: JsonObject, val retry: Boolean = false)

@Serializable
data class AtlasAnswer(val revision: String, val items: List<AtlasItem>, val counts: AtlasCounts, val evidence: AtlasEvidence,
    val omissions: List<String> = emptyList(), val next: List<AtlasNext> = emptyList(), val sources: List<AtlasSource> = emptyList(),
    val queries: List<AtlasReply> = emptyList(), val reads: List<AtlasReply> = emptyList())

@Serializable
data class AtlasReply(val asked: JsonObject, val answer: AtlasAnswer? = null, val source: AtlasSource? = null, val error: String? = null,
    val next: List<AtlasNext> = emptyList())

@Serializable
data class AtlasRead(
    @Describe("A handle from atlas.query") val id: String? = null,
    @Describe("A workspace-relative file; with name it narrows the search") val path: String? = null,
    @Describe("Up to 16 workspace-relative files read together, such as their imports before an edit") val paths: List<String> = emptyList(),
    @Describe("Zero-based line inside the declaration") val line: Int? = null,
    @Describe("Zero-based UTF-16 column") val column: Int? = null,
    @Describe("declaration: its own source; outline: the file's declarations; imports: the file's package and import lines; file: the file text") val part: AtlasPart = AtlasPart.Declaration,
    @Describe("Largest text in characters, 1000 to 22000") val budget: Int = 12000,
    @Describe("Characters of the file to skip, to read a long file in pieces") val offset: Int = 0,
    @Describe("A declaration to read without a handle; Type.member or Receiver.function picks one owner, and a name with up to four declarations, such as overloads, reads each") val name: String? = null,
    @Describe("Narrows a name: class, interface, object, enum, function, property or typealias") val kind: String? = null,
    @Describe("A position as answers print it: path:line or path:line:column") val at: String? = null,
)

@Serializable
data class AtlasSource(val revision: String, val id: String, val kind: String, val path: String, val digest: String, val signature: String? = null,
    val at: String? = null, val text: String, val totalChars: Int, val partial: Boolean, val next: List<AtlasNext> = emptyList(), val line: Int? = null,
    val also: List<AtlasSource> = emptyList())

@Serializable
data class AtlasGraphRequest(
    @Describe("The part to read, from 0") val page: Int = 0,
    @Describe("The revision part 0 returned; a later part of another revision is refused, because the code changed since") val revision: String? = null,
    @Describe("Characters per part, from 20000 to 250000; read every part of one graph with the same value") val chars: Int = 40_000,
)

@Serializable
data class AtlasGraphCounts(val modules: Int, val sourceSets: Int, val files: Int, val declarations: Int, val uses: Int, val actuals: Int,
    val appGraphs: Int = 0, val appNodes: Int = 0)

@Serializable
data class AtlasGraphPage(
    val ready: Boolean,
    val revision: String? = null,
    val page: Int = 0,
    val pages: Int = 0,
    val progress: String? = null,
    val counts: AtlasGraphCounts? = null,
    val omissions: List<String> = emptyList(),
    val shape: JsonElement? = null,
)

@Serializable enum class AtlasCapability {
    @SerialName("syntax") Syntax, @SerialName("semantic") Semantic, @SerialName("build") Build, @SerialName("reaktor") Reaktor,
    @SerialName("runtime") Runtime, @SerialName("rewriter") Rewriter, @SerialName("checks") Checks,
}

@Serializable
data class AtlasProvider(val name: String, val capabilities: List<AtlasCapability>, val languages: List<String>, val evidence: AtlasStrength,
    val cost: String, val state: String, val ready: Boolean)

@Serializable
data class AtlasProvidersRequest(@Describe("Only the providers of this capability: syntax, semantic, build, reaktor, runtime, rewriter or checks") val capability: AtlasCapability? = null)

@Serializable
data class AtlasProviders(val providers: List<AtlasProvider>, val missing: List<String>)

open class AtlasApi : Service("reaktor.atlas") {
    open val query by call<AtlasQuery, AtlasAnswer>(effect = Effect.ReadOnly, summary = "Answers a code question in one bounded call, across this repository and the builds it includes: a Kotlin, TypeScript or C++ declaration by name (Type.member works), its uses with the declaration each sits in and every other mention, what a declaration or file calls and the types it uses, literal text in any tracked file, a file's declarations, build nodes and operations. queries and reads answer more questions and atlas_read requests in the same call. Presets: map gives the workspace at a glance; impact and affected-checks give what a change reaches and the checks that cover it; explain and wiring answer how reaktor-graph graphs, nodes, ports and routes fit together; runtime and routes say which nodes ran and which routes a flow opens. Use it before rg or reading whole files.")
    open val read by call<AtlasRead, AtlasSource>(effect = Effect.ReadOnly, summary = "Reads only what a question needs, with line numbers: a declaration's source by name, handle or path:line (overloads come together), the outline or imports of one file or several, or a window of any text file. Use it instead of reading a whole file.")
    open val graph by call<AtlasGraphRequest, AtlasGraphPage>(effect = Effect.ReadOnly, summary = "Returns the code graph of this repository and the builds it includes as one reaktor-graph GraphShape, sent in parts: modules and source sets are scopes, each Kotlin file and each TypeScript file of a tsconfig project is a node, its top-level declarations are the ports it provides, as is each class member another file uses once the compiler has indexed both, and each use of another file's declaration is a wire, resolved by the Kotlin or TypeScript compiler for indexed files and otherwise matched by name, imports and package. An expect declaration also takes a wire from each of its actuals. The app graphs declared in the source are scopes under app, with a node per attached node class or route, the wires the autoWire rule infers, a wire to the file that declares each node class, and the definition ids running graphs give the same nodes. Read part 0, then parts 1 to pages - 1 with its revision and the same chars. While the graph is being built the answer is not ready and says how far it got; ask again. It is large: Hangar's Code graph view draws it, and atlas.query answers a question about one declaration or file in one call.")
    open val providers by call<AtlasProvidersRequest, AtlasProviders>(effect = Effect.ReadOnly, summary = "Lists the providers the Atlas answers from, each a node on the kernel graph: what it gives (syntax, semantic, build, reaktor, runtime, rewriter or checks), for which languages, the evidence it rests on, what it costs and its state now, such as how many Kotlin files the compiler has indexed; and each capability no ready provider gives")
}

