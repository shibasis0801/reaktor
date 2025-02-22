package dev.shibasis.reaktor.auth.db.users

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.db.UserEntity
import dev.shibasis.reaktor.auth.db.Users
import dev.shibasis.reaktor.auth.framework.CrudRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.springframework.stereotype.Component

@Component
class UserRepository(
    database: Database
): CrudRepository<Long, User, UserEntity, UserEntity.Companion>(database, UserEntity) {
    fun getUser(appId: Long, socialId: String) = sql {
        companion.find { (Users.socialId eq socialId) and (Users.appId eq appId) }
            .firstOrNull() ?: throw NullPointerException("User not found")
    }
}
































