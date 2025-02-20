package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.auth.db.Contexts.long
import dev.shibasis.reaktor.auth.db.Contexts.uuid
import dev.shibasis.reaktor.auth.db.Permissions.autoGenerate
import dev.shibasis.reaktor.auth.db.Permissions.autoIncrement
import dev.shibasis.reaktor.auth.RowData
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

inline fun <reified T : Any> Table.jsonb(name: String): Column<T> = jsonb(name, { Json.encodeToString(
    serializer<T>(), it) }, { Json.decodeFromString(serializer<T>(), it) })

abstract class Auditable<IdType: Comparable<IdType>>(
    name: String
): IdTable<IdType>(name) {
    val data = jsonb<JsonElement>("data")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    fun entityId(id: IdType) = EntityID(id, this)
    fun<T: Comparable<T>> foreignKey(table: IdTable<T>, name: String) = reference(name, table, onDelete = ReferenceOption.CASCADE)
}

abstract class LongAuditable(
    name: String,
    keyColumn: String = "id"
): Auditable<Long>(name) {
    final override val id = long(keyColumn).autoIncrement().entityId()
    final override val primaryKey = PrimaryKey(id)
}
abstract class UUIDAuditable(
    name: String,
    keyColumn: String = "id"
): Auditable<UUID>(name) {
    final override val id = uuid(keyColumn).autoIncrement().entityId()
    final override val primaryKey = PrimaryKey(id)
}

interface AuditableEntity<Row>: DTOConverter<Row> {
    val data: JsonElement
    val createdAt: LocalDateTime
    val updatedAt: LocalDateTime

    fun getRowData() = RowData(data, createdAt, updatedAt)
}

abstract class LongAuditableEntity<Row>(
    id: EntityID<Long>,
    auditable: Auditable<Long>
): LongEntity(id), AuditableEntity<Row> {
    override val updatedAt by auditable.updatedAt
    override val createdAt by auditable.createdAt
    override var data by auditable.data
}

abstract class UUIDAuditableEntity<Row>(
    id: EntityID<UUID>,
    auditable: Auditable<UUID>
): UUIDEntity(id), AuditableEntity<Row> {
    override val updatedAt by auditable.updatedAt
    override val createdAt by auditable.createdAt
    override var data by auditable.data
}

