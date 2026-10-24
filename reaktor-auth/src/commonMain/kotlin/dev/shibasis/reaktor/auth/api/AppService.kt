package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import kotlinx.serialization.Serializable
import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service

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


open class AppService : Service("reaktor.apps") {
    override val access = ServiceAccess(Callers.SignedIn)

    open val getAll by get<Request, AppResponse>("/apps/")
    open val getApp by get<Request, AppResponse>("/apps/{id}")
}
