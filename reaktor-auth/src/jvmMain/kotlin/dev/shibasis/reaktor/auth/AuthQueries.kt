package dev.shibasis.reaktor.auth

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction


fun Apps.query(id: Long): App? {
    return transaction {
        val match = Apps.selectAll().where { Apps.id eq id }.firstOrNull()
        val app = match?.let {
            App(
                id = it[Apps.id].value,
                name = it[Apps.name],
                data = it[Apps.data],
                createdAt = it[Apps.createdAt],
                updatedAt = it[Apps.updatedAt]
            )
        }



        app
    }
}