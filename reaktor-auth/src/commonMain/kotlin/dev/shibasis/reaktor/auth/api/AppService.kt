package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.io.service.GetHandler
import dev.shibasis.reaktor.io.service.Request
import dev.shibasis.reaktor.io.service.Response
import dev.shibasis.reaktor.io.service.RequestHandler
import dev.shibasis.reaktor.io.service.Service
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


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
    override val getApp = GetHandler<Request, AppResponse>("") {
        http.get(route) {
            it.headers
            it.pathParams
            it.queryParams
        }.body<AppResponse>()
    }
}
