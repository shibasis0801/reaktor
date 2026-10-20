package dev.shibasis.reaktor.code

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.provides
import java.nio.file.Path

class CodeWorkspaceNode(graph: Graph, root: Path, generatedRoots: () -> Collection<Path> = { emptyList() }) : BasicNode(graph) {
    override val label = "Code intelligence"
    val code by provides(CodeWorkspace(root, generatedRoots))
}
