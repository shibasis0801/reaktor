package dev.shibasis.reaktor.blueprint.gallery

import dev.shibasis.reaktor.blueprint.BlueprintEdge
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintGroup
import dev.shibasis.reaktor.blueprint.BlueprintLayout
import dev.shibasis.reaktor.blueprint.BlueprintNode
import dev.shibasis.reaktor.blueprint.ElkFrameLayouter
import dev.shibasis.reaktor.blueprint.FrameLayouter
import dev.shibasis.reaktor.blueprint.GridFrameLayouter
import dev.shibasis.reaktor.blueprint.Lanes
import dev.shibasis.reaktor.blueprint.Layered
import dev.shibasis.reaktor.blueprint.LayeredEdge
import dev.shibasis.reaktor.blueprint.LayeredFrameLayouter
import dev.shibasis.reaktor.blueprint.LayeredNode
import dev.shibasis.reaktor.blueprint.LayeredSpacing
import dev.shibasis.reaktor.blueprint.LayoutSketch
import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.blueprint.PinSpec
import dev.shibasis.reaktor.graph.core.PortWiring
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

class LayoutGalleryTest {
    private val out = File("build/reports/layout-gallery").apply { mkdirs() }
    private val summary = StringBuilder()

    private class Fixture(val groups: List<BlueprintGroup>, val edges: List<BlueprintEdge>, val labels: Map<String, String>, val aspect: Double)

    private fun appGraph(): Fixture {
        val lines = AppGraphShape.trim().lines().map { it.split(' ') }
        val nodes = lines.filter { it[0] == "n" }
        val edges = lines.filter { it[0] == "e" }
        val usedOut = HashMap<Int, MutableSet<Int>>()
        val usedIn = HashMap<Int, MutableSet<Int>>()
        edges.forEach { e -> if (e[3] == "d") { e[4].toInt().takeIf { it >= 0 }?.let { usedOut.getOrPut(e[1].toInt()) { HashSet() } += it }; e[5].toInt().takeIf { it >= 0 }?.let { usedIn.getOrPut(e[2].toInt()) { HashSet() } += it } } }
        val lane = mapOf("d" to 0, "c" to 1, "s" to 2)
        val blueprint = nodes.mapIndexed { i, n ->
            n[1].toInt() to BlueprintNode("n$i", lane.getValue(n[2]),
                inputs = usedIn[i].orEmpty().sorted().map { PinSpec("i$it", "T", PortWiring.Connected) },
                outputs = usedOut[i].orEmpty().sorted().map { PinSpec("o$it", "T", PortWiring.Connected) })
        }
        val groups = blueprint.groupBy({ it.first }, { it.second }).entries.sortedBy { it.key }.map { (g, members) -> BlueprintGroup("feature:$g", "Feature $g", members) }
        val wires = edges.filter { it[3] != "c" }.mapIndexed { i, e ->
            val route = e[3] == "r"
            BlueprintEdge("w$i", "n${e[1]}", "n${e[2]}", e[4].toInt().takeIf { it >= 0 && !route }?.let { "o$it" }, e[5].toInt().takeIf { it >= 0 && !route }?.let { "i$it" },
                if (route) LinkKind.Route else LinkKind.Wire)
        }
        val labels = nodes.indices.associate { i -> "n$i" to "${mapOf("d" to "Data", "c" to "Container", "s" to "Screen").getValue(nodes[i][2])} $i" }
        return Fixture(groups, wires, labels, 16.0 / 9.0)
    }

    private fun agentBoard(): Fixture {
        val graphs = mapOf(
            "G1" to ("Ship onboarding" to listOf("Design flow", "Build screens", "API endpoints", "Wire screens to API", "QA pass", "Release notes", "Analytics events")),
            "G2" to ("Payments" to listOf("Pick provider", "Checkout UI", "Webhooks", "Refund flow", "Load test")),
        )
        val dependencies = mapOf("G1" to listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 4, 4 to 5, 2 to 6), "G2" to listOf(0 to 1, 0 to 2, 2 to 3, 1 to 4, 2 to 4))
        val board = listOf("Fix login crash", "Update docs", "Triage inbox", "Rotate keys")
        val labels = HashMap<String, String>()
        val after = ArrayList<Pair<String, String>>()
        graphs.forEach { (id, plan) -> plan.second.forEachIndexed { i, title -> labels["$id-$i"] = title }; dependencies.getValue(id).forEach { (a, b) -> after += "$id-$a" to "$id-$b" } }
        board.forEachIndexed { i, title -> labels["T$i"] = title }
        after += "T0" to "T1"
        val runs = listOf("S1" to "G1-0", "S1" to "G1-1", "S2" to "G1-2", "S3" to "G1-3", "S3" to "G2-1", "S4" to "G2-0", "S5" to "T0", "S6" to "G2-2", "S6" to "G2-3")
        val upstream = after.groupBy({ it.second }, { it.first })
        val depth = HashMap<String, Int>()
        fun lane(id: String): Int = depth.getOrPut(id) { upstream[id].orEmpty().maxOfOrNull { lane(it) + 1 } ?: 0 }
        val worked = runs.map { it.second }.toSet()
        fun task(id: String) = BlueprintNode(id, lane(id), inputs = listOfNotNull(PinSpec("after", "Outcome", PortWiring.Connected), PinSpec("work", "Session", if (id in worked) PortWiring.Connected else PortWiring.Unused)),
            outputs = listOf(PinSpec("done", "Outcome", PortWiring.Connected)))
        val groups = buildList {
            add(BlueprintGroup("board", "Board tasks", board.indices.map { task("T$it") }))
            graphs.forEach { (id, plan) ->
                labels["coordinator:$id"] = "Coordinator"
                add(BlueprintGroup("graph:$id", "$id · ${plan.first}", listOf(BlueprintNode("coordinator:$id", 0)) + plan.second.indices.map { task("$id-$it") }))
            }
            val sessions = runs.map { it.first }.distinct()
            sessions.forEach { labels[it] = "Session $it" }
            add(BlueprintGroup("sessions", "Sessions", sessions.map { BlueprintNode(it, 0, outputs = listOf(PinSpec("runs", "Session", PortWiring.Connected))) }))
        }
        val edges = after.map { (a, b) -> BlueprintEdge("after:$a:$b", a, b, "done", "after") } + runs.map { (s, t) -> BlueprintEdge("runs:$s:$t", s, t, "runs", "work") }
        return Fixture(groups, edges, labels, 2.4)
    }

    private fun pipeline(): Fixture {
        val stages = listOf("Ingest", "Validate", "Normalize", "Enrich", "Score", "Rank", "Review", "Publish", "Archive")
        val pin = PortWiring.Connected
        fun card(id: String, lane: Int) = BlueprintNode(id, lane, inputs = listOf(PinSpec("in", "T", pin)), outputs = listOf(PinSpec("out", "T", pin)))
        val labels = HashMap<String, String>()
        val groups = stages.mapIndexed { i, stage ->
            val cards = (0 until 2 + i % 3).map { j -> "$stage-$j".also { labels[it] = "$stage step ${j + 1}" } }
            BlueprintGroup("stage:$i", stage, cards.mapIndexed { j, id -> card(id, j) })
        }
        val edges = ArrayList<BlueprintEdge>()
        groups.forEach { group -> group.nodes.zipWithNext().forEach { (a, b) -> edges += BlueprintEdge("${a.id}>${b.id}", a.id, b.id, "out", "in") } }
        groups.zipWithNext().forEach { (a, b) -> edges += BlueprintEdge("${a.nodes.last().id}>${b.nodes.first().id}", a.nodes.last().id, b.nodes.first().id, "out", "in") }
        edges += BlueprintEdge("skip", groups[2].nodes.last().id, groups[4].nodes.first().id, "out", "in")
        return Fixture(groups, edges, labels, 16.0 / 9.0)
    }

    private fun atlas(): Fixture {
        val pin = PortWiring.Connected
        fun source(id: String, rows: Int) = BlueprintNode(id, 0, inputs = (0 until rows).map { PinSpec("r$it", "chapter", pin, it) }, outputs = (0 until rows).map { PinSpec("r$it", "chapter", pin, it) }, rows = rows)
        val labels = HashMap<String, String>()
        val linear = BlueprintGroup("linear", "Linear Algebra", (0 until 4).map { BlueprintNode("linear/$it", it % 2).also { node -> labels[node.id] = "Chapter ${it + 1}" } }, lanes = Lanes.Free)
        val groups = listOf(
            BlueprintGroup("math", "Mathematics", listOf(source("calculus", 4), source("analysis", 4), source("measure", 3), source("topology", 2), source("logic", 2), BlueprintNode("linear", 0, group = linear)), lanes = Lanes.Free),
            BlueprintGroup("physics", "Physics", listOf(source("mechanics", 3), source("waves", 2), source("electromagnetism", 3), source("quantum", 4)), lanes = Lanes.Free),
            BlueprintGroup("cs", "Computer Science", listOf(source("algorithms", 3), source("learning", 4), source("systems", 2), source("compilers", 2)), lanes = Lanes.Free),
        )
        groups.flatMap { it.nodes }.filter { it.group == null }.forEach { labels[it.id] = it.id.replaceFirstChar(Char::uppercase) }
        val edges = listOf(
            BlueprintEdge("r1", "calculus", "analysis", family = "prerequisite"), BlueprintEdge("r2", "analysis", "measure", family = "prerequisite"),
            BlueprintEdge("r3", "calculus", "mechanics", family = "prerequisite"), BlueprintEdge("r4", "mechanics", "waves", family = "prerequisite"),
            BlueprintEdge("r5", "waves", "electromagnetism", family = "prerequisite"), BlueprintEdge("r6", "linear/3", "quantum", family = "prerequisite"),
            BlueprintEdge("r7", "linear/2", "learning", family = "prerequisite"), BlueprintEdge("r8", "algorithms", "learning", family = "prerequisite"),
            BlueprintEdge("r9", "measure", "learning", family = "prerequisite"), BlueprintEdge("r10", "logic", "compilers", family = "prerequisite"),
            BlueprintEdge("r11", "linear/0", "linear/1"), BlueprintEdge("r12", "linear/1", "linear/3"),
            BlueprintEdge("c1", "calculus", "quantum", "r2", "r1", family = "chapter", curved = true), BlueprintEdge("c2", "analysis", "algorithms", "r3", "r0", family = "chapter", curved = true),
        )
        return Fixture(groups, edges, labels, 16.0 / 9.0)
    }

    private class Catalogue(val nodes: List<LayeredNode>, val edges: List<LayeredEdge>, val labels: Map<String, String>)

    private fun catalogue(): Catalogue {
        val sources = linkedMapOf(
            "calculus" to listOf("Limits", "Derivatives", "Integrals", "Series"),
            "analysis" to listOf("Sequences", "Continuity", "Differentiation", "Riemann integral"),
            "measure" to listOf("Sigma algebras", "Lebesgue measure", "Integration", "Lp spaces"),
            "linear" to listOf("Vectors", "Matrices", "Eigenvalues", "Inner products"),
            "functional" to listOf("Banach spaces", "Hilbert spaces", "Operators"),
            "probability" to listOf("Probability spaces", "Random variables", "Expectation", "Limit theorems"),
            "fourier" to listOf("Fourier series", "Fourier transform"),
        )
        val titles = mapOf("calculus" to "Calculus", "analysis" to "Real Analysis", "measure" to "Measure Theory", "linear" to "Linear Algebra",
            "functional" to "Functional Analysis", "probability" to "Probability", "fourier" to "Fourier Analysis")
        val labels = HashMap<String, String>()
        val nodes = ArrayList<LayeredNode>()
        val edges = ArrayList<LayeredEdge>()
        sources.forEach { (id, chapters) ->
            labels["source:$id"] = titles.getValue(id)
            nodes += LayeredNode("source:$id", 180.0, 76.0, anchor = 12.0)
            chapters.forEachIndexed { i, title ->
                labels["unit:$id:$i"] = title
                nodes += LayeredNode("unit:$id:$i", 180.0, 76.0, anchor = 12.0)
                edges += LayeredEdge("contains:$id:$i", "source:$id", "unit:$id:$i", weight = 4)
            }
        }
        listOf("calculus" to "analysis", "analysis" to "measure", "measure" to "probability", "linear" to "functional", "measure" to "functional", "analysis" to "fourier", "linear" to "fourier")
            .forEach { (a, b) -> edges += LayeredEdge("r:$a>$b", "source:$a", "source:$b") }
        listOf("calculus:0" to "analysis:0", "calculus:2" to "analysis:3", "analysis:3" to "measure:2", "measure:1" to "probability:0", "measure:2" to "probability:2",
            "measure:3" to "functional:0", "linear:3" to "functional:1", "calculus:3" to "fourier:0").forEach { (a, b) -> edges += LayeredEdge("c:$a>$b", "unit:$a", "unit:$b") }
        return Catalogue(nodes, edges, labels)
    }

    private fun record(name: String, layout: BlueprintLayout<*>, labels: Map<String, String>) {
        val (boxes, lines) = LayoutSketch.of(layout, labels)
        val metrics = LayoutSketch.metrics(boxes, lines)
        LayoutSketch.draw(boxes, lines, File(out, "$name.png"), "$name  ·  $metrics")
        summary.appendLine("$name: $metrics")
    }

    private fun recordPlain(name: String, boxes: List<LayoutSketch.Box>, lines: List<LayoutSketch.Line>) {
        val metrics = LayoutSketch.metrics(boxes, lines)
        LayoutSketch.draw(boxes, lines, File(out, "$name.png"), "$name  ·  $metrics")
        summary.appendLine("$name: $metrics")
    }

    private fun variants(name: String, fixture: Fixture) {
        listOf<Pair<String, FrameLayouter>>("elk" to ElkFrameLayouter, "grid" to GridFrameLayouter, "layered" to LayeredFrameLayouter).forEach { (variant, layouter) ->
            record("$name-$variant", BlueprintEngine.layout(name, fixture.groups, fixture.edges, fixture.aspect, layouter), fixture.labels)
        }
    }

    @Test fun gallery() {
        variants("app", appGraph())
        variants("agent", agentBoard())
        variants("pipeline", pipeline())
        variants("atlas", atlas())
        val catalogue = catalogue()
        val force = forcePositions(catalogue.nodes.size, catalogue.edges.map { e -> catalogue.nodes.indexOfFirst { it.id == e.from } to catalogue.nodes.indexOfFirst { it.id == e.to } })
        recordPlain("subgraph-force", catalogue.nodes.mapIndexed { i, n -> LayoutSketch.Box(n.id, force[i].first - 90, force[i].second - 12, 180.0, 76.0, catalogue.labels.getValue(n.id)) },
            catalogue.edges.map { e ->
                val a = force[catalogue.nodes.indexOfFirst { it.id == e.from }]; val b = force[catalogue.nodes.indexOfFirst { it.id == e.to }]
                LayoutSketch.Line(e.from, e.to, listOf(a, b))
            })
        val layered = Layered.layout(catalogue.nodes, catalogue.edges, LayeredSpacing(node = 16.0, layer = 104.0, edge = 9.0, component = 72.0, wrap = 12))
        recordPlain("subgraph-layered", catalogue.nodes.mapIndexed { i, n -> LayoutSketch.Box(n.id, layered.x[i], layered.y[i], 180.0, 76.0, catalogue.labels.getValue(n.id)) },
            catalogue.edges.mapIndexed { i, e -> LayoutSketch.Line(e.from, e.to, layered.routes[i].toList().chunked(2) { it[0] to it[1] }) })
        File(out, "metrics.txt").writeText(summary.toString())
        println(summary)
    }

    private fun forcePositions(count: Int, links: List<Pair<Int, Int>>): List<Pair<Double, Double>> {
        val px = DoubleArray(count) { i -> cos(i * 2.399963229728653) * 110 * sqrt(i.toDouble()) }
        val py = DoubleArray(count) { i -> sin(i * 2.399963229728653) * 110 * sqrt(i.toDouble()) }
        val fx = DoubleArray(count)
        val fy = DoubleArray(count)
        repeat(140) { step ->
            fx.fill(0.0); fy.fill(0.0)
            for (i in 0 until count) for (j in 0 until i) {
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val distance = max(hypot(dx, dy), 1.0)
                val clearance = max(sqrt((dx / 200) * (dx / 200) + (dy / 110) * (dy / 110)), .01)
                val strength = 6500 / (distance * distance) + if (clearance < 1.2) (1.2 - clearance) * 18 else 0.0
                fx[i] += dx / distance * strength; fy[i] += dy / distance * strength
                fx[j] -= dx / distance * strength; fy[j] -= dy / distance * strength
            }
            links.forEach { (i, j) ->
                val dx = px[j] - px[i]; val dy = py[j] - py[i]
                val distance = max(hypot(dx, dy), 1.0)
                val force = (distance - 210) * .025
                fx[i] += dx / distance * force; fy[i] += dy / distance * force
                fx[j] -= dx / distance * force; fy[j] -= dy / distance * force
            }
            for (i in 0 until count) {
                val gx = fx[i] - px[i] * .006; val gy = fy[i] - py[i] * .006
                val limit = 14 * (1 - step / 160.0)
                val scale = max(1.0, hypot(gx, gy) / limit)
                px[i] += gx / scale; py[i] += gy / scale
            }
        }
        val minX = px.minOrNull() ?: 0.0; val minY = py.minOrNull() ?: 0.0
        return (0 until count).map { (px[it] - minX + 100) to (py[it] - minY + 20) }
    }
}
