package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.step.InputNode
import dev.shibasis.reaktor.graph.step.Output

interface Durable<I : Any, O : Any> {
    val workflowId: String
    val childGraph: Graph
    val input: InputNode<I>
    val output: Output<O>
    val requirement: AuthRequirement get() = AuthRequirement()
    val operatorRetry: AuthRequirement? get() = null
}
