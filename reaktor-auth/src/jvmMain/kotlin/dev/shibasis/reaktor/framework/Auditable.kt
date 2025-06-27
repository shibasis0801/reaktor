package dev.shibasis.reaktor.framework

import dev.shibasis.reaktor.auth.AuditableDto
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

/**
 * Custom Exposed column type for storing a kotlinx.datetime.Instant
 * in a column that is timezone-aware (e.g., TIMESTAMP WITH TIME ZONE in PostgreSQL).
 */
class KotlinInstantWithTimeZoneColumnType : ColumnType<Instant>(), IDateColumnType {
    override val hasTimePart: Boolean = true

    // This is the key: it uses the SQL type for timezone-aware timestamps.
    override fun sqlType(): String = currentDialect.dataTypeProvider.timestampWithTimeZoneType()

    // Converts a value from the database to an Instant.
    // The JDBC driver will often return OffsetDateTime for timestamptz columns.
    override fun valueFromDB(value: Any): Instant = when (value) {
        is OffsetDateTime -> value.toInstant().toKotlinInstant()
        is java.sql.Timestamp -> value.toInstant().toKotlinInstant()
        is String -> Instant.parse(value)
        is java.sql.Date -> value.toKotlinInstant()
        else -> error("Unexpected value for Instant: $value of ${value::class.qualifiedName}")
    }

    // Converts an Instant from your application code to a database-compatible object.
    // We convert our Instant to an OffsetDateTime at UTC, as this is a standard
    // representation that JDBC drivers handle correctly for timestamptz.
    override fun notNullValueToDB(value: Instant): Any {
        return OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC)
    }

    // Provides a default string representation for SQL statements.
    override fun nonNullValueAsDefaultString(value: Instant): String {
        val instantAsStr = notNullValueToDB(value).toString()
        return "'$instantAsStr'::${sqlType()}"
    }
}

fun Table.timestampZ(name: String): Column<Instant> {
    return registerColumn(name, KotlinInstantWithTimeZoneColumnType())
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

