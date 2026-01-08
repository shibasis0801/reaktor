package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.*
import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.auth.Users
import dev.shibasis.reaktor.framework.CrudRepository
import dev.shibasis.reaktor.framework.ExposedAdapter
import dev.shibasis.reaktor.graph.service.Request
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AppRepository(adapter: ExposedAdapter): CrudRepository(adapter) {
    suspend fun findById(
        request: Request,
        id: UUID
    ) = request.sql {
        Apps.selectAll()
            .where { Apps.id eq id }
            .map { Apps.toDto(it) }
            .firstOrNull()
    }

    suspend fun all(
        request: Request
    ) = request.sql {
        Apps.selectAll()
            .map { Apps.toDto(it) }
    }

    suspend fun findByName(
        request: Request,
        name: String
    ) = request.sql {
        Apps.selectAll()
            .where { Apps.name eq name }
            .map { Apps.toDto(it) }
            .firstOrNull()
    }
}


@Component
class UserRepository(adapter: ExposedAdapter): CrudRepository(adapter) {
    suspend fun findByAppIdAndProvider(
        request: Request,
        appId: UUID,
        socialId: String,
        provider: UserProvider
    ) = request.sql {
        Users.selectAll()
            .where { (Users.appId eq appId) and (Users.provider eq provider) and (Users.socialId eq socialId) }
            .map { Users.toDto(it) }
            .firstOrNull()
    }

    suspend fun upsert(
        request: Request,
        user: User
    ) = request.sql {
        val data = Users.upsertReturning { it.fields(user) }.toList()
        data.firstOrNull()?.let { Users.toDto(it) }
    }
}
