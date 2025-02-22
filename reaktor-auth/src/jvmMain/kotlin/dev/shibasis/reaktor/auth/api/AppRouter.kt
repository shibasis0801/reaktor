package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.db.AppEntity
import dev.shibasis.reaktor.auth.db.apps.AppRepository
import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.errorResponse
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.core.network.ErrorMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

@Component
class AppRouter(
    private val appRepository: AppRepository
): Router() {
    override fun router() = coRouter {
        GET("/") {
            appRepository
                .all()
                .fold(
                    { jsonResponse(it.map(AppEntity::toDto)) },
                    { errorResponse(1, it.message ?: "Unknown") }
                )
        }

        GET("/{id}") {
            val id = it.pathVariable("id")
            val numericId = id.toLongOrNull()
            val result = if (numericId != null) {
                appRepository.find(numericId)
            } else {
                appRepository.findByName(id)
            }

            result.fold(
                { jsonResponse(it.toDto()) },
                { errorResponse(1, it.message ?: "Unknown") }
            )
        }
    }
}
