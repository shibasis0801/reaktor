package dev.shibasis.reaktor.db.sql

sealed class SqlType<T>(val typeName: String)
object IntType: SqlType<Int>("INT")
object LongType: SqlType<Long>("BIGINT")
object StringType: SqlType<String>("VARCHAR")
object BoolType: SqlType<Boolean>("BOOLEAN")

