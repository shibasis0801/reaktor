package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.service.contract.Call

fun <In, Out> Workflow.call(operation: Call<In, Out>): CallPort<In, Out> =
    CallPort(registerConsumer<suspend (In) -> Out>(Key(operation.name), callType(operation)), operation)
