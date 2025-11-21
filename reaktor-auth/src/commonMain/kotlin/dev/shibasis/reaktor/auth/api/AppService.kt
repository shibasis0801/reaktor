package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.LogicNode
import dev.shibasis.reaktor.graph.core.connect
import dev.shibasis.reaktor.graph.core.provides
import dev.shibasis.reaktor.graph.core.requires
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.PostHandler
import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.RequestHandler
import dev.shibasis.reaktor.graph.service.Service
import kotlinx.serialization.Serializable


@Serializable
sealed class AppResponse(
    override val statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
): Response() {
    @Serializable
    data class Success(val apps: List<App>): AppResponse(StatusCode.OK)
    @Serializable
    data class Failure(val error: ErrorMessage): AppResponse(StatusCode.BAD_REQUEST)
}


abstract class AppService: Service() {
    abstract val getAll: GetHandler<Request, AppResponse>
    abstract val getApp: GetHandler<Request, AppResponse>
}

abstract class AppClient: AppService() {
    override val getApp = GetHandler<Request, AppResponse>("")
}



class First(
    graph: Graph,
    appService: AppService
): LogicNode(graph) {
    val getData by provides<AppService>(appService)
}


class Second(graph: Graph): LogicNode(graph) {
    val getData by requires<AppService>()


    init {
        getData {
            getApp
        }
    }
}


fun t(first: First, second: Second) {
    connect(first, second)
}

