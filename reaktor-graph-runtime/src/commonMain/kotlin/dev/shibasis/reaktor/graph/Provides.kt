package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.portgraph.port.ProviderPort

interface Provides<out T : Any> {
    val port: ProviderPort<out T>
}
