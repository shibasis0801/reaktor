package dev.shibasis.reaktor.db.sql

interface SqlConstruct {
    fun toSql(): String
}
sealed class Statement: SqlConstruct
sealed class Expression : SqlConstruct
