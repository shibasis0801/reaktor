package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import dev.shibasis.reaktor.io.service.RequestHandler
import dev.shibasis.reaktor.io.service.Service
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


@Serializable
sealed class AppResponse(
    @Transient override var statusCode: StatusCode = StatusCode.OK,
    @Transient override val headers: MutableMap<String, String> = mutableMapOf(),
): BaseResponse {
    @Serializable
    data class Success(val apps: List<App>): AppResponse(StatusCode.OK)
    @Serializable
    data class Failure(val error: ErrorMessage): AppResponse(StatusCode.BAD_REQUEST)
}


abstract class AppService: Service() {
    abstract val getAll: RequestHandler<BaseRequest, AppResponse>
    abstract val getApp: RequestHandler<BaseRequest, AppResponse>
}

abstract class AppClient: AppService() {
    override val getApp = GetHandler<BaseRequest, AppResponse>("") {
        http.get(route) {
            it.headers
            it.pathParams
            it.queryParams
        }.body<AppResponse>()
    }
}
