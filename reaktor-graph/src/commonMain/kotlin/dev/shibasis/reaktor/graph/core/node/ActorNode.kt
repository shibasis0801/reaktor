package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.graph.core.Graph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel


/*
incomplete design.
must mimic https://github.com/cloudflare/actors (DurableObject wrapper),
take inspiration from erlang/akka/orleans

This would allow combining functionality from everywhere.
persistence through object-database/sqlite
timers through workmanager/bgtaskscheduler/quartz/etc
etc
*/
open class ActorNode<Message>(
    graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatch.Default.coroutineDispatcher
): Node(graph, dispatcher.limitedParallelism(1)) {
    val channel = Channel<Message>()
}
