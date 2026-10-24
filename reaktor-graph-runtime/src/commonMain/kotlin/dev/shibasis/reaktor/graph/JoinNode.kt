package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.service.contract.Channel
import dev.shibasis.reaktor.service.contract.Keyed
import dev.shibasis.reaktor.service.contract.TemporalJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

class JoinNode<K, L, R, O> internal constructor(
    graph: Graph,
    val left: Channel<L>,
    val right: Channel<R>,
    val result: Channel<O>,
    leftKey: Keyed<L, K>,
    rightKey: Keyed<R, K>,
    val join: TemporalJoin<K, L, R, O>,
) : BasicNode(graph), ShapeAttributes {
    override val kind: NodeKind get() = NodeKind.Service

    override val label: String = "join ${left.id} + ${right.id}"

    private val lock = Mutex()

    val output by publishes(result)

    val lefts by subscribes(left, name = "join.left") { value ->
        emit(lock.withLock { join.left(leftKey.key(value), leftKey.time(value), value) })
    }

    val rights by subscribes(right, name = "join.right") { value ->
        emit(lock.withLock { join.right(rightKey.key(value), rightKey.time(value), value) })
    }

    private suspend fun emit(results: List<O>) = results.forEach { output(it) }

    override val shapeAttributes: Map<String, String>
        get() = mapOf(
            "join" to "temporal",
            "within" to join.within.toString(),
            "lateness" to join.lateness.toString(),
            "result" to result.id,
            "late" to join.late.toString(),
        )
}

fun <K, L, R, O> Graph.join(
    left: Channel<L>,
    right: Channel<R>,
    result: Channel<O>,
    within: Duration,
    leftKey: Keyed<L, K>,
    rightKey: Keyed<R, K>,
    lateness: Duration = Duration.ZERO,
    combine: (L, R) -> O,
): JoinNode<K, L, R, O> =
    JoinNode(this, left, right, result, leftKey, rightKey, TemporalJoin(within, lateness, combine)).also { attach(it) }
