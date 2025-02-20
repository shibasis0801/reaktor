package dev.shibasis.reaktor.auth.db.apps

import dev.shibasis.reaktor.auth.db.AppEntity
import dev.shibasis.reaktor.auth.db.Apps
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.sql.Database
import org.springframework.stereotype.Component

@Component
class AppRepository(database: Database): CrudRepository<Long, AppEntity, AppEntity.Companion>(database, AppEntity) {
    fun findByName(name: String): Result<AppEntity> = sql {
        entity.find { Apps.name eq name }
            .firstOrNull() ?: throw NullPointerException("App not found")
    }
}