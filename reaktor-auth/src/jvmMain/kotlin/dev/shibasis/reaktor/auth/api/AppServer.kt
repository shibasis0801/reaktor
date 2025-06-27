package dev.shibasis.reaktor.auth.api


import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.core.network.ErrorMessage
import dev.shibasis.reaktor.io.service.BaseRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AppServer(
    private val appRepository: AppRepository
): AppService() {
    override val getAll = GetHandler<BaseRequest, AppResponse>("/") {
        appRepository
            .all(it)
            .fold(
                { AppResponse.Success(it) },
                { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
            )
    }

    override val getApp = GetHandler<BaseRequest, AppResponse>("/{id}") { request ->
        val id = request.pathParams["id"] ?: return@GetHandler AppResponse.Failure(ErrorMessage(1, "Invalid id"))

        runCatching {
            UUID.fromString(id)
        }.fold(
            { appRepository.findById(request, it) },
            { appRepository.findByName(request, id) }
        ).fold(
            { AppResponse.Success(listOf(it)) },
            { AppResponse.Failure(ErrorMessage(1, it.message ?: "Unknown")) }
        )
    }
}

