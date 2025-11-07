package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.service.GetHandler
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
    abstract val getAll: RequestHandler<Request, AppResponse>
    abstract val getApp: RequestHandler<Request, AppResponse>
}

abstract class AppClient: AppService() {
    override val getApp = GetHandler<Request, AppResponse>("")
}
