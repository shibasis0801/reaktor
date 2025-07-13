package dev.shibasis.reaktor.framework

import dev.shibasis.reaktor.auth.AuditableDto
import dev.shibasis.reaktor.framework.db.timestampZ
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.IDateColumnType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.json.jsonb
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

inline fun <reified T : Any> Table.jsonb(name: String): Column<T> = jsonb(name, { Json.encodeToString(
    serializer<T>(), it) }, { Json.decodeFromString(serializer<T>(), it) })

fun java.sql.Date.toKotlinInstant(): Instant {
    val date = toLocalDate()
    val javaInstant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
    return javaInstant.toKotlinInstant()
}

abstract class Auditable<IdType: Comparable<IdType>, DTO: AuditableDto>(
    name: String
): IdTable<IdType>(name) {
    val data = jsonb<JsonElement>("data")
    val createdAt = timestampZ("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestampZ("updated_at").defaultExpression(CurrentTimestamp)

    fun entityId(id: IdType) = EntityID(id, this)
    fun<T: Comparable<T>> foreignKey(table: IdTable<T>, name: String) = reference(name, table, onDelete = ReferenceOption.CASCADE)

    abstract fun toDto(result: ResultRow): DTO

    fun UpdateBuilder<*>.auditableFields(dto: DTO) {
        this[data] = dto.data
        this[createdAt] = dto.createdAt
        this[updatedAt] = dto.updatedAt
    }

    abstract fun UpdateBuilder<*>.selfFields(dto: DTO)

    fun UpdateBuilder<*>.fields(dto: DTO) {
        auditableFields(dto)
        selfFields(dto)
    }
}

abstract class LongAuditable<DTO: AuditableDto>(
    name: String,
    keyColumn: String = "id"
): Auditable<Long, DTO>(name) {
    final override val id = long(keyColumn).autoIncrement().entityId()
    final override val primaryKey = PrimaryKey(id)
}

abstract class UUIDAuditable<DTO: AuditableDto>(
    name: String,
    keyColumn: String = "id"
): Auditable<UUID, DTO>(name) {
    final override val id = uuid(keyColumn).autoGenerate().entityId()
    final override val primaryKey = PrimaryKey(id)
}

