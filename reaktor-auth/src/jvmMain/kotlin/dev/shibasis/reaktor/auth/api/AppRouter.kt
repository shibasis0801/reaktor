package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.AppEntity
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.invoke
import dev.shibasis.reaktor.auth.service.AppResponse
import dev.shibasis.reaktor.auth.service.AppService
import dev.shibasis.reaktor.auth.utils.toDto
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import dev.shibasis.reaktor.io.service.EmptyRequest
import dev.shibasis.reaktor.io.service.HttpMethod
import dev.shibasis.reaktor.io.service.RequestHandler
import dev.shibasis.reaktor.io.service.Service
import io.ktor.client.request.request
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import java.util.UUID

@Component
class AppServer(
    private val appRepository: AppRepository
): AppService() {
    override val getAll = GetHandler<BaseRequest, AppResponse>("/") {
        appRepository {
            findAll().toList()
        }.fold(
            { AppResponse.Success(it.map(AppEntity::toDto)) },
            { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
        )
    }

    override val getApp = GetHandler<BaseRequest, AppResponse>("/{id}") {
        val id = it.pathParams["id"] ?: return@GetHandler AppResponse.Failure(ErrorMessage(1, "Invalid id"))
        runCatching {
            UUID.fromString(id)
        }.fold(
            { appRepository { findById(it) ?: throw IllegalArgumentException(it.toString()) } },
            { appRepository { findByName(id) ?: throw IllegalArgumentException(id) } }
        ).fold(
            { AppResponse.Success(listOf(it.toDto())) },
            { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
        )
    }
}

fun HttpMethod.toSpring(): org.springframework.http.HttpMethod = org.springframework.http.HttpMethod.valueOf(name)
fun StatusCode.toHttpStatus(): HttpStatus = HttpStatus.valueOf(code)

val textSerializer = TextSerializer()

fun Service.toRouter(): RouterFunction<ServerResponse> {
    val builder = RouterFunctions.route()
    handlers.forEach {
        @Suppress("UNCHECKED_CAST")
        val handler = it as RequestHandler<BaseRequest, BaseResponse>

        val predicate = RequestPredicates.method(handler.method.toSpring())
            .and(RequestPredicates.path(baseUrl + handler.route))
            .and(RequestPredicates.accept(MediaType.APPLICATION_JSON))

        builder.route(predicate) { req ->
            mono {
                val request: BaseRequest = when(handler.method) {
                    HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD ->
                        textSerializer.deserialize(EmptyRequest.serializer(), "{}")
                    else -> {
                        val body = req.bodyToMono(String::class.java).awaitSingleOrNull() ?: "{}"
                        textSerializer.deserialize(handler.requestSerializer, body)
                    }
                }

                request.pathParams.putAll(req.pathVariables())
                req.queryParams().forEach { (key, values) ->
                    values.firstOrNull()?.let { value -> request.queryParams[key] = value }
                }

                val response = handler(request)

                ServerResponse.status(response.statusCode.toHttpStatus())
                    .headers { httpHeaders -> response.headers.forEach(httpHeaders::add) }
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValueAndAwait(textSerializer.serialize(handler.responseSerializer, response))
            }
        }
    }
    return builder.build()
}


fun CoRouterFunctionDsl.nest(route: String, service: Service) {
    route.nest {
        add(service.toRouter())
    }
}
