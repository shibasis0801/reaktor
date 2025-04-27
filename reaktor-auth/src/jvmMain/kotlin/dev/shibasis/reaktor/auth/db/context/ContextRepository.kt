package dev.shibasis.reaktor.auth.db.context

import dev.shibasis.reaktor.auth.Context
import dev.shibasis.reaktor.auth.db.Apps
import dev.shibasis.reaktor.auth.db.ContextEntity
import dev.shibasis.reaktor.auth.db.Contexts
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ContextRepository(database: Database) : CrudRepository<UUID, Context, ContextEntity, ContextEntity.Companion>(database, ContextEntity) {
    fun findByAppId(appId: UUID): Result<List<ContextEntity>> = sql {
        companion.find { Contexts.appId eq EntityID(appId, Apps) }
            .toList()
    }
}