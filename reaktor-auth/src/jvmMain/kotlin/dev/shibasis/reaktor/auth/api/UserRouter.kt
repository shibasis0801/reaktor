package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.framework.Router
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.coRouter

// todo These will also need auth.
@Component
class UserRouter(
//    private val userRepository: UserRepository
): Router() {
    override fun router() = coRouter {
//        GET("/") {
//            errorResponse(1,"Unknown")
//            userRepository {
//                findAll().toList()
//            }.fold(
//                    { jsonResponse(it.map(UserEntity::toDto)) },
//                    { errorResponse(1, it.message ?: "Unknown") }
//                )
//        }

//        GET("/{id}") {
//            errorResponse(1,"Unknown")
//            val id = it.pathVariable("id") ?: return@GET errorResponse(2, "Invalid ID")
//            runCatching {
//                UUID.fromString(id)
//            }.fold(
//                { userRepository { findById(it) ?: throw IllegalArgumentException(it.toString()) } },
//                { userRepository { findByName(id) ?: throw IllegalArgumentException(id) } }
//            ).fold(
//                { jsonResponse(it .toDto()) },
//                { errorResponse(1, it.message ?: "Unknown") }
//            )
//        }
    }
}
