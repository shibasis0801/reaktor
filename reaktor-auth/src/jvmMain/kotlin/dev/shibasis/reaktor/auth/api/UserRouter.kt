package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.UserEntity
import dev.shibasis.reaktor.auth.db.UserRepository
import dev.shibasis.reaktor.auth.db.invoke
import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.errorResponse
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.auth.utils.toDto
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.coRouter
import java.util.UUID

// todo These will also need auth.
@Component
class UserRouter(
    private val userRepository: UserRepository
): Router() {
    override fun router() = coRouter {
        GET("/") {
            userRepository {
                findAll().toList()
            }.fold(
                    { jsonResponse(it.map(UserEntity::toDto)) },
                    { errorResponse(1, it.message ?: "Unknown") }
                )
        }

        GET("/{id}") {
            val id = it.pathVariable("id") ?: return@GET errorResponse(2, "Invalid ID")
            runCatching {
                UUID.fromString(id)
            }.fold(
                { userRepository { findById(it) ?: throw IllegalArgumentException(it.toString()) } },
                { userRepository { findByName(id) ?: throw IllegalArgumentException(id) } }
            ).fold(
                { jsonResponse(it .toDto()) },
                { errorResponse(1, it.message ?: "Unknown") }
            )
        }
    }
}
