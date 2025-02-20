package dev.shibasis.reaktor.auth.framework

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.exceptions.EntityNotFoundException
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder


abstract class CrudRepository<ID: Comparable<ID>, out TableEntity: Entity<ID>, TableEntityClass: EntityClass<ID, TableEntity>>(
    database: Database,
    protected val entity: TableEntityClass
): ExposedAdapter(database) {
    protected fun notFound(id: ID) = EntityNotFoundException(EntityID(id, entity.table), entity)

    fun find(id: ID) = sql {
        entity.findById(id)
    }

    fun find(op: SqlExpressionBuilder.() -> Op<Boolean>) = sql {
        entity.find(op)
    }

    fun create(block: (TableEntity) -> Unit) = sql {
        entity.new { block(this) }
    }

    fun update(id: ID, block: (TableEntity) -> Unit) = sql {
        val instance = entity.findById(id)
        instance?.apply { block(this) } ?: throw notFound(id)

    }

    fun delete(id: ID) = sql {
        val instance = entity.findById(id)
        instance?.delete() ?: throw notFound(id)
    }

    fun all() = sql {
        entity.all().toList()
    }
}