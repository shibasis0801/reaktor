package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.db.AppEntity
import dev.shibasis.reaktor.auth.db.UserEntity
import dev.shibasis.reaktor.auth.db.users.UserRepository
import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.errorResponse
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.core.network.ErrorMessage
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.coRouter
import kotlin.jvm.optionals.getOrNull

// todo These will also need auth.
@Component
class UserRouter(
    private val userRepository: UserRepository
): Router() {
    override fun router() = coRouter {
        GET("/") {
            userRepository
                .all()
                .fold(
                    { jsonResponse(it.map(UserEntity::toDto)) },
                    { errorResponse(1, it.message ?: "Unknown") }
                )
        }

        GET("/{id}") {
            val id = it.pathVariable("id").toLongOrNull() ?: return@GET errorResponse(2, "Invalid ID")
            userRepository.find(id).fold(
                { jsonResponse(it.toDto()) },
                { errorResponse(1, it.message ?: "Unknown") }
            )
        }
    }
}
