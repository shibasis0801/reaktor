package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import java.io.File

fun createSqliteObjectDatabase(
    name: String,
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    timestampProvider: TimestampProvider = DefaultTimestampProvider()
): SqliteObjectDatabase {
    val dbFile = File("$name.db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    return SqliteObjectDatabase(driver, name, objectSerializer, timestampProvider)
}
