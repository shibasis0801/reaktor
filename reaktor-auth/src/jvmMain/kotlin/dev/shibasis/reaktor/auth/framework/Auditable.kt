package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.auth.RowData
import dev.shibasis.reaktor.auth.db.Apps.autoIncrement
import dev.shibasis.reaktor.auth.db.Apps.entityId
import dev.shibasis.reaktor.auth.db.Apps.long
import dev.shibasis.reaktor.auth.db.Apps.uuid
import dev.shibasis.reaktor.auth.db.Sessions.autoGenerate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
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

fun longPrimaryKey(name: String = "id"): () -> Column<EntityID<Long>> = { long(name).autoIncrement().entityId() }
fun uuidPrimaryKey(name: String = "id"): () -> Column<EntityID<UUID>> = { uuid(name).autoGenerate().entityId() }

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
    final override val id = uuid(keyColumn).autoGenerate().entityId()
    final override val primaryKey = PrimaryKey(id)
}

abstract class AuditableEntity<IdType: Comparable<IdType>, Row>(
    id: EntityID<IdType>,
    auditable: Auditable<IdType>
): Entity<IdType>(id) {
    var data by auditable.data
    val createdAt by auditable.createdAt
    val updatedAt by auditable.updatedAt

    abstract fun toDto(): Row
    abstract fun fromDto(dto: Row)
    fun getRowData() = RowData(data, createdAt, updatedAt)
}
//
//abstract class LongAuditableEntity<Row>(
//    id: EntityID<Long>,
//    auditable: LongAuditable
//): AuditableEntity<Long, Row>(id, auditable)
//
//abstract class UUIDAuditableEntity<Row>(
//    id: EntityID<UUID>,
//    auditable: UUIDAuditable
//): AuditableEntity<UUID, Row>(id, auditable)
//

//
open class AuditableEntityCompanion<
        IdType: Comparable<IdType>,
        Row,
        out Entity: AuditableEntity<IdType, Row>
>(
    val auditable: Auditable<IdType>,
    entityType: Class<Entity>? = null,
    entityCtor: ((EntityID<IdType>) -> Entity)? = null
): EntityClass<IdType, Entity>(auditable, entityType, entityCtor)

//
//abstract class LongAuditableEntityCompanion<Row, out Entity: LongAuditableEntity<Row>>(
//    table: Auditable<Long>,
//    entityType: Class<Entity>? = null,
//    entityCtor: ((EntityID<Long>) -> Entity)? = null
//): AuditableEntityCompanion<Long, Row, Entity>(table, entityType, entityCtor)
//
//abstract class UUIDAuditableEntityCompanion<Row, out Entity: UUIDAuditableEntity<Row>>(
//    table: Auditable<UUID>,
//    entityType: Class<Entity>? = null,
//    entityCtor: ((EntityID<UUID>) -> Entity)? = null
//): AuditableEntityCompanion<UUID, Row, Entity>(table, entityType, entityCtor)