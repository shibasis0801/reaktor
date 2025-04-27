package dev.shibasis.reaktor.auth.db.apps

import dev.shibasis.reaktor.auth.App
import dev.shibasis.reaktor.auth.db.AppEntity
import dev.shibasis.reaktor.auth.db.Apps
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.sql.Database
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AppRepository(database: Database): CrudRepository<UUID, App, AppEntity, AppEntity.Companion>(database, AppEntity) {
    fun findByName(name: String): Result<AppEntity> = sql {
        companion.find { Apps.name eq name }
            .firstOrNull() ?: throw NullPointerException("App not found")
    }
}