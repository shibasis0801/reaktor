package dev.shibasis.reaktor.auth.framework

import org.jetbrains.exposed.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter

abstract class CrudRepository<
        IdType: Comparable<IdType>,
        RowType: Any,
        out TableEntity: AuditableEntity<IdType, RowType>,
        TableEntityCompanion: AuditableEntityCompanion<IdType, RowType, TableEntity>
>(
    database: Database,
    val companion: TableEntityCompanion
): ExposedAdapter(database) {
    protected fun notFound(id: IdType) = EntityNotFoundException(EntityID(id, companion.table), companion)

    fun find(id: IdType) = sql {
        companion.findById(id)
    }

    fun find(op: SqlExpressionBuilder.() -> Op<Boolean>) = sql {
        companion.find(op)
    }

    fun create(block: (TableEntity) -> Unit) = sql {
        companion.new { block(this) }
    }
    fun create(data: RowType) = create { it.fromDto(data) }

    fun update(id: IdType, block: (TableEntity) -> Unit) = sql {
        val instance = companion.findById(id)
        instance?.apply { block(this) } ?: throw notFound(id)
    }

    fun delete(id: IdType) = sql {
        val instance = companion.findById(id)
        instance?.delete() ?: throw notFound(id)
    }

    fun all() = sql {
        companion.all().toList()
    }
}