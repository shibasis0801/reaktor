package dev.shibasis.reaktor.blueprint

import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkConnectableShape
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

actual val DefaultFrameLayouter: FrameLayouter = ElkFrameLayouter

object ElkFrameLayouter : FrameLayouter {
    private val reported = AtomicBoolean()

    override fun lay(group: BlueprintGroup, pins: Map<String, List<Pin>>, edges: List<FrameEdge>): FrameLayout = try {
        layout(group, pins, edges)
    } catch (error: Throwable) {
        if (error is CancellationException || error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
        if (reported.compareAndSet(false, true)) Logger.getLogger("Reaktor.Blueprint").log(Level.WARNING, "ELK layout failed; using grid layout", error)
        val grid = GridFrameLayouter.lay(group, pins, edges)
        FrameLayout(grid.cards, grid.links, grid.width, grid.height, error)
    }

    private fun layout(group: BlueprintGroup, pins: Map<String, List<Pin>>, edges: List<FrameEdge>): FrameLayout {
        val root = ElkGraphUtil.createGraph()
        configure(root)
        if (group.loose) root.setProperty(CoreOptions.ASPECT_RATIO, 1.4)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
        val boxes = linkedMapOf<String, ElkNode>()
        val ports = hashMapOf<String, ElkConnectableShape>()
        val width = BlueprintEngine.CardWidth
        val middle = BlueprintEngine.HeaderHeight / 2
        val nodes = group.nodes.associateBy { it.id }
        val backwards = edges.filterTo(hashSetOf()) { !group.loose && nodes.getValue(it.from).lane > nodes.getValue(it.to).lane }
        val incoming = edges.mapTo(hashSetOf()) { if (it in backwards) it.from else it.to }
        val outgoing = edges.mapTo(hashSetOf()) { if (it in backwards) it.to else it.from }
        val firstLane = group.nodes.minOfOrNull { it.lane }
        val lastLane = group.nodes.maxOfOrNull { it.lane }
        group.nodes.forEach { node ->
            val box = ElkGraphUtil.createNode(root)
            boxes[node.id] = box
            if (!group.loose && firstLane != lastLane) box.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, when {
                node.lane == firstLane && node.id !in incoming -> LayerConstraint.FIRST
                node.lane == lastLane && node.id !in outgoing -> LayerConstraint.LAST
                else -> LayerConstraint.NONE
            })
            box.width = width
            box.height = BlueprintEngine.cardHeight(node)
            box.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_POS)
            pins.getValue(node.id).forEach { pin ->
                ports["${node.id}${if (pin.provides) ">" else "<"}${pin.key}"] = ElkGraphUtil.createPort(box).apply {
                    setDimensions(1.0, 1.0)
                    setLocation(if (pin.provides) width else -1.0, pin.y)
                    setProperty(CoreOptions.PORT_SIDE, if (pin.provides) PortSide.EAST else PortSide.WEST)
                }
            }
            ports["${node.id}<"] = ElkGraphUtil.createPort(box).apply { setDimensions(1.0, 1.0); setLocation(-1.0, middle); setProperty(CoreOptions.PORT_SIDE, PortSide.WEST) }
            ports["${node.id}>"] = ElkGraphUtil.createPort(box).apply { setDimensions(1.0, 1.0); setLocation(width, middle); setProperty(CoreOptions.PORT_SIDE, PortSide.EAST) }
        }
        val laidEdges = linkedMapOf<FrameEdge, ElkEdge>()
        edges.forEach { edge ->
            val source = ports["${edge.from}>${edge.fromPort.orEmpty()}"] ?: ports.getValue("${edge.from}>")
            val target = ports["${edge.to}<${edge.toPort.orEmpty()}"] ?: ports.getValue("${edge.to}<")
            val backward = edge in backwards
            laidEdges[edge] = ElkGraphUtil.createSimpleEdge(if (backward) target else source, if (backward) source else target).also(ElkGraphUtil::updateContainment)
        }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        val cards = boxes.mapValues { (id, box) -> Card(id, box.x, box.y, box.width, box.height, pins.getValue(id), nodes.getValue(id).folded) }
        val links = laidEdges.map { (edge, laid) ->
            val points = laid.sections.flatMap { section ->
                listOf(section.startX to section.startY) + section.bendPoints.map { it.x to it.y } + listOf(section.endX to section.endY)
            }
            Link(edge.id, edge.from, edge.to, edge.fromPort, edge.toPort, edge.kind, edge.members, if (edge in backwards) points.asReversed() else points, reversed = edge.reversed)
        }
        require(root.width.isFinite() && root.height.isFinite() && cards.values.all { it.x.isFinite() && it.y.isFinite() } &&
            links.all { it.points.isNotEmpty() && it.points.all { point -> point.first.isFinite() && point.second.isFinite() } }) {
            "ELK returned incomplete geometry"
        }
        return FrameLayout(cards, links, root.width, root.height)
    }

    private fun configure(node: ElkNode) {
        node.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
        node.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        node.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        node.setProperty(CoreOptions.SPACING_NODE_NODE, BlueprintEngine.CardGap)
        node.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, BlueprintEngine.LayerGap)
        node.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 16.0)
        node.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, 7.0)
        node.setProperty(CoreOptions.SPACING_EDGE_EDGE, 7.0)
        node.setProperty(CoreOptions.SPACING_EDGE_NODE, 12.0)
        node.setProperty(CoreOptions.RANDOM_SEED, 7)
    }
}
