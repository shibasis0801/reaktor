package dev.shibasis.reaktor.auth.db.users

import dev.shibasis.reaktor.auth.db.UserEntity
import dev.shibasis.reaktor.auth.db.Users
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Component

@Component
class UserRepository(
    database: Database
): CrudRepository<Long, UserEntity, UserEntity.Companion>(database, UserEntity) {
    fun getUser(appId: Long, socialId: String): Result<UserEntity> {
        return find { (Users.socialId eq socialId) and (Users.appId eq appId) }
            .map { it.firstOrNull() ?: throw NullPointerException("Not Found") }
    }
}
































