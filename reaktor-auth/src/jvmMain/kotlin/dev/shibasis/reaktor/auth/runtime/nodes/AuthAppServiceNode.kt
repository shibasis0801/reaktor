package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.AppResponse
import dev.shibasis.reaktor.auth.api.AppService
import dev.shibasis.reaktor.auth.runtime.AuthAppService
import dev.shibasis.reaktor.auth.runtime.ports.AuthAppCatalog
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import java.util.UUID

class AuthAppServiceNode(graph: Graph) : BasicNode(graph), AuthAppService {
    val appCatalogPort by consumes<AuthAppCatalog>()
    val appServicePort by provides<AuthAppService>(this)

    override val service: AppService = object : AppService("") {
        override val getAll = GetHandler<Request, AppResponse>("/") { request ->
            appCatalogPort.suspended {
                all(request).fold(
                    { AppResponse.Success(it) },
                    { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) },
                )
            }
        }

        override val getApp = GetHandler<Request, AppResponse>("/{id}") { request ->
            val id = request.pathParams["id"]
                ?: return@GetHandler AppResponse.Failure(ErrorMessage(1, "Invalid id"))

            appCatalogPort.suspended {
                runCatching { UUID.fromString(id) }
                    .fold(
                        { findById(request, it) },
                        { findByName(request, id) },
                    )
                    .fold(
                        { AppResponse.Success(listOfNotNull(it)) },
                        { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) },
                    )
            }
        }
    }
}
