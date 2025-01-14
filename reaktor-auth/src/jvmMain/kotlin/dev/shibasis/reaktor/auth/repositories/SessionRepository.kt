package dev.shibasis.reaktor.auth.repositories

import dev.shibasis.reaktor.auth.utils.ExposedAdapter
import dev.shibasis.reaktor.auth.Sessions
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

class SessionRepository(
    database: Database
) : ExposedAdapter(database) {
    fun getSession(id: UUID) = sql {
        Sessions.selectAll().where { Sessions.id eq id }.singleOrNull()?.run(Sessions::toDto)
    }

    fun getSessionCount(userId: Long, appId: Long) = sql {
        Sessions.selectAll().where { (Sessions.userId eq userId) and (Sessions.appId eq appId) }.count()
    }

    fun createSession(userId: Long, appId: Long, expiresAt: LocalDateTime) = sql {
        Sessions.insertAndGetId {
            it[Sessions.userId] = userId
            it[Sessions.appId] = appId
            it[Sessions.expiresAt] = expiresAt
        }.value
    }

    fun deleteSession(id: UUID) = sql {
        Sessions.deleteWhere { Sessions.id eq id }
    }
}