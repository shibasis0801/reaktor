package dev.shibasis.reaktor.graph.selection

import kotlinx.serialization.Serializable

@Serializable
data class SearchNode(val id: String, val label: String, val kind: String = "node", val text: String = "", val parent: String? = null)

@Serializable
data class SearchEdge(val id: String, val from: String, val to: String, val kind: String, val label: String = "")

@Serializable
data class SearchGraph(val id: String, val revision: String, val nodes: List<SearchNode>, val edges: List<SearchEdge>) {
    fun query(request: SubgraphQuery): SubgraphResult {
        require(request.limit in 1..500 && request.depth in 0..3) { "Choose 1–500 nodes and 0–3 hops" }
        require(request.query.length <= 4000 && request.seeds.size <= request.limit) { "The query is too large" }
        val byId = nodes.associateBy { it.id }
        require(request.seeds.all { it in byId }) { "A seed is outside this graph" }
        val terms = request.query.lowercase().split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.length > 2 && it !in StopWords }.distinct()
        val ranked = nodes.mapNotNull { node ->
            val title = node.label.lowercase()
            val body = "${node.id} ${node.kind} ${node.text}".lowercase()
            val matches = terms.filter { it in title || it in body }
            val score = matches.sumOf { if (it in title) 4 else 1 }
            if (score == 0) null else Triple(node.id, score, matches)
        }.sortedWith(compareByDescending<Triple<String, Int, List<String>>> { it.second }.thenBy { it.first })
        val reasons = linkedMapOf<String, String>()
        request.seeds.forEach { reasons[it] = "Selected by the caller" }
        ranked.take((request.limit - reasons.size).coerceAtLeast(0)).forEach { (id, _, matches) -> if (id !in reasons) reasons[id] = "Matches: ${matches.joinToString()}" }
        val adjacent = mutableMapOf<String, MutableList<Pair<String, SearchEdge>>>()
        edges.filter { it.from in byId && it.to in byId && (request.kinds.isEmpty() || it.kind in request.kinds) }.forEach { edge ->
            if (request.direction != "upstream") adjacent.getOrPut(edge.from) { mutableListOf() }.add(edge.to to edge)
            if (request.direction != "downstream") adjacent.getOrPut(edge.to) { mutableListOf() }.add(edge.from to edge)
        }
        require(request.direction in setOf("both", "upstream", "downstream")) { "Unknown graph direction" }
        var frontier = reasons.keys.toList()
        var truncated = ranked.count { it.first !in reasons } > 0
        repeat(request.depth) {
            val next = mutableListOf<String>()
            frontier.forEach { from -> adjacent[from].orEmpty().sortedBy { it.first }.forEach { (id, edge) ->
                if (id !in reasons) {
                    if (reasons.size < request.limit) { reasons[id] = "${edge.kind}: ${byId.getValue(from).label}"; next += id }
                    else truncated = true
                }
            } }
            frontier = next
        }
        return SubgraphResult(id, revision, reasons.keys.map(byId::getValue), edges.filter { it.from in reasons && it.to in reasons && (request.kinds.isEmpty() || it.kind in request.kinds) }, reasons, truncated)
    }

    fun resolve(selection: Subgraph): SubgraphResolution {
        require(selection.graphId == id) { "This subgraph belongs to another graph" }
        val known = nodes.associateBy { it.id }
        val members = selection.nodes.toSet()
        val present = members.filterTo(linkedSetOf()) { it in known }
        val available = edges.filter { it.from in present && it.to in present }.associateBy { it.id }
        val chosen = selection.edges?.mapNotNull(available::get) ?: available.values.toList()
        return SubgraphResolution(
            present.map(known::getValue), chosen,
            selection.nodes.filter { it !in known }, selection.edges.orEmpty().filter { it !in available },
            edges.filter { (it.from in present) != (it.to in present) }, selection.sourceRevision != revision,
        )
    }

    fun validate(selection: Subgraph) {
        require(selection.id.isNotBlank() && selection.id.length <= 160 && selection.title.isNotBlank() && selection.title.length <= 200) { "Name this subgraph" }
        require(selection.intent.length <= 4000 && selection.revision > 0 && selection.status in setOf("draft", "saved")) { "Invalid subgraph metadata" }
        require(selection.nodes.isNotEmpty() && selection.nodes.size <= 500 && selection.nodes.distinct().size == selection.nodes.size) { "Select 1–500 distinct nodes" }
        require(selection.edges == null || selection.edges.size <= 5000 && selection.edges.distinct().size == selection.edges.size) { "Select distinct relationships" }
        require(selection.reasons.size <= 500 && selection.reasons.all { (id, reason) -> id in selection.nodes && reason.length <= 2000 }) { "Reasons must describe selected nodes" }
        val resolved = resolve(selection)
        require(resolved.missingNodes.isEmpty() && resolved.missingEdges.isEmpty()) { "The selection contains unavailable nodes or relationships" }
    }

    private companion object {
        val StopWords = setOf("the", "and", "for", "with", "from", "want", "that", "this", "have", "into", "able", "study", "work", "project", "should", "would")
    }
}

@Serializable
data class SubgraphQuery(val query: String = "", val seeds: List<String> = emptyList(), val depth: Int = 1, val direction: String = "both", val kinds: List<String> = emptyList(), val limit: Int = 80)

@Serializable
data class SubgraphResult(val graphId: String, val sourceRevision: String, val nodes: List<SearchNode>, val edges: List<SearchEdge>, val reasons: Map<String, String>, val truncated: Boolean) {
    fun proposal(id: String, title: String, intent: String) = Subgraph(id, title, intent, graphId, sourceRevision, nodes.map { it.id }, edges.map { it.id }, reasons)
}

@Serializable
data class Subgraph(
    val id: String, val title: String, val intent: String, val graphId: String, val sourceRevision: String,
    val nodes: List<String>, val edges: List<String>? = null, val reasons: Map<String, String> = emptyMap(),
    val status: String = "draft", val revision: Int = 1,
)

@Serializable
data class SubgraphResolution(val nodes: List<SearchNode>, val edges: List<SearchEdge>, val missingNodes: List<String>, val missingEdges: List<String>, val boundary: List<SearchEdge>, val stale: Boolean)
