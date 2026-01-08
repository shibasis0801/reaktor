package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import java.io.File

fun createSqliteObjectDatabase(
    name: String,
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    cachePolicy: CachePolicy = CachePolicyLRU(100),
    timestampProvider: TimestampProvider = DefaultTimestampProvider()
): SqliteObjectDatabase {
    val dbFile = File("$name.db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    // JdbcSqliteDriver.Schema.create(driver) // Not needed as SqliteObjectDatabase creates the table
    return SqliteObjectDatabase(driver, name, objectSerializer, cachePolicy, timestampProvider)
}
