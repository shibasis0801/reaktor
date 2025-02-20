package dev.shibasis.reaktor.auth.db.context

import dev.shibasis.reaktor.auth.db.Apps
import dev.shibasis.reaktor.auth.db.ContextEntity
import dev.shibasis.reaktor.auth.db.Contexts
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.springframework.stereotype.Component

@Component
class ContextRepository(database: Database) : CrudRepository<Long, ContextEntity, ContextEntity.Companion>(database, ContextEntity) {
    fun findByAppId(appId: Long): Result<List<ContextEntity>> = sql {
        entity.find { Contexts.appId eq EntityID(appId, Apps) }
            .toList()
    }
}