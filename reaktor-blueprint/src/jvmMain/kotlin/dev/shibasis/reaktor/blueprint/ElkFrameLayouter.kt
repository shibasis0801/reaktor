package dev.shibasis.reaktor.blueprint

import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkGraphElement
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil

actual val DefaultFrameLayouter: FrameLayouter = ElkFrameLayouter

object ElkFrameLayouter : FrameLayouter {
    private val reported = AtomicBoolean()

    override fun lay(request: FrameRequest): FrameResult = try {
        layout(request.graph)
    } catch (error: Throwable) {
        if (error is CancellationException || error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
        if (reported.compareAndSet(false, true)) Logger.getLogger("Reaktor.Blueprint").log(Level.WARNING, "ELK layout failed; using grid layout", error)
        GridFrameLayouter.lay(request).copy(failure = error)
    }

    private fun layout(graph: ElkGraph): FrameResult {
        val root = ElkGraphUtil.createGraph().configure(graph.options)
        val ports = HashMap<String, ElkPort>()
        val boxes = graph.boxes.map { box ->
            ElkGraphUtil.createNode(root).configure(box.options).apply {
                setDimensions(box.width, box.height)
                box.pins.forEach { pin ->
                    ports[pin.id] = ElkGraphUtil.createPort(this).apply {
                        setDimensions(1.0, 1.0)
                        setLocation(pin.x, pin.y)
                        setProperty(CoreOptions.PORT_SIDE, if (pin.east) PortSide.EAST else PortSide.WEST)
                    }
                }
            }
        }
        val edges = graph.lines.map { line -> ElkGraphUtil.createSimpleEdge(ports.getValue(line.source), ports.getValue(line.target)).also(ElkGraphUtil::updateContainment) }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        val nodes = DoubleArray(boxes.size * 2) { if (it % 2 == 0) boxes[it / 2].x else boxes[it / 2].y }
        val lines = edges.map { edge ->
            edge.sections.flatMap { section -> listOf(section.startX, section.startY) + section.bendPoints.flatMap { listOf(it.x, it.y) } + listOf(section.endX, section.endY) }.toDoubleArray()
        }
        require(root.width.isFinite() && root.height.isFinite() && nodes.all(Double::isFinite) && lines.all { it.isNotEmpty() && it.all(Double::isFinite) }) {
            "ELK returned incomplete geometry"
        }
        return FrameResult(root.width, root.height, nodes, lines)
    }

    private fun <T : ElkGraphElement> T.configure(options: Map<String, String>): T = apply {
        options.forEach { (key, value) ->
            val option = checkNotNull(LayoutMetaDataService.getInstance().getOptionDataBySuffix(key)) { "Unknown ELK option $key" }
            setProperty(option, checkNotNull(option.parseValue(value)) { "Invalid ELK value $value for $key" })
        }
    }
}
