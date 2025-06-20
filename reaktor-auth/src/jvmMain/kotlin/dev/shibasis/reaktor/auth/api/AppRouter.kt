package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.AppEntity
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.invoke
import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.service.AppResponse
import dev.shibasis.reaktor.auth.service.AppService
import dev.shibasis.reaktor.auth.utils.toDto
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.io.service.BaseRequest
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.coRouter

//
@Component
class AppServer(
    private val appRepository: AppRepository
): AppService() {
    override val getAll = GetHandler<BaseRequest, AppResponse>("/") {
//        AppResponse.Failure(ErrorMessage(1, "Unknown"))
        appRepository {
            findAll().toList()
        }.fold(
            { AppResponse.Success(it.map(AppEntity::toDto)) },
            { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
        )
    }

    override val getApp = GetHandler<BaseRequest, AppResponse>("/{id}") {
        AppResponse.Failure(ErrorMessage(1, "Invalid id"))
//        val id = it.pathParams["id"] ?: return@GetHandler AppResponse.Failure(ErrorMessage(1, "Invalid id"))
//        runCatching {
//            UUID.fromString(id)
//        }.fold(
//            { appRepository { findById(it) ?: throw IllegalArgumentException(it.toString()) } },
//            { appRepository { findByName(id) ?: throw IllegalArgumentException(id) } }
//        ).fold(
//            { AppResponse.Success(listOf(it.toDto())) },
//            { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
//        )
    }
}
//
//
//
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
//
//
//inline fun<reified Request: BaseRequest, reified Response: BaseResponse>
//CoRouterFunctionDsl.attach(
//    requestHandler: RequestHandler<Request, Response>
//) {
//    when(requestHandler.method) {
//        GET -> GET(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//        POST -> POST(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//        PUT -> PUT(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//        DELETE -> DELETE(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//        PATCH -> PATCH(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//        OPTIONS -> OPTIONS(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//        HEAD -> HEAD(requestHandler.route) {
//            toServerResponse<Response>(requestHandler.invoke(it.awaitBody<Request>()))
//        }
//    }
//}
