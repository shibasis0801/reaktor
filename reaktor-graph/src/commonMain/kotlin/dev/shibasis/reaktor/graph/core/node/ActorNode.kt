package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.framework.Dispatch
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
    graph: dev.shibasis.reaktor.graph.core.Graph,
    dispatcher: CoroutineDispatcher = Dispatch.Default.coroutineDispatcher.limitedParallelism(1)
): Node(graph) {
    val channel = Channel<Message>()
}
