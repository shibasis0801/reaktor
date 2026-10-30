package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.over

fun <S : Service> PortCapability.client(service: S): S =
    service over PortLink(registerConsumer<S>(Key(service.id), contractType(service)))
