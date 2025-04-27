package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.db.AppEntity
import dev.shibasis.reaktor.auth.db.apps.AppRepository
import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.errorResponse
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.auth.framework.toHttpStatus
import dev.shibasis.reaktor.auth.service.AppResponse
import dev.shibasis.reaktor.auth.service.AppService
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import dev.shibasis.reaktor.io.service.Service
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import java.util.UUID

@Component
class AppServer(
    private val appRepository: AppRepository
): AppService() {
    override val getAll = GetHandler<BaseRequest, AppResponse>("/") {
        appRepository
            .all()
            .fold(
                { AppResponse.Success(it.map(AppEntity::toDto)) },
                { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
            )
    }

    override val getApp = GetHandler<BaseRequest, AppResponse>("/{id}") {
        val id = it.pathParams["id"] ?: return@GetHandler AppResponse.Failure(ErrorMessage(1, "Invalid id"))

        runCatching {
            UUID.fromString(id)
        }.fold(
            { appRepository.find(it) },
            { appRepository.findByName(id) }
        ).fold(
            { AppResponse.Success(listOf(it.toDto())) },
            { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
        )
    }
}



@Component
class AppRouter(
    private val appServer: AppServer
): Router() {
    override fun router() = coRouter {
        appServer.getApp.apply {
            GET(route) {
                toServerResponse<AppResponse>(invoke(it.toBaseRequest()))
            }
        }
        appServer.getAll.apply {
            GET(route) {
                toServerResponse<AppResponse>(invoke(it.toBaseRequest()))
            }
        }
    }
}
