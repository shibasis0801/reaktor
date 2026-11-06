package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlin.test.AfterTest

class SqliteWorkConformanceTest : WorkConformance() {
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    override suspend fun database(): ObjectDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        return SqliteObjectDatabase(driver, "work", journalStorePrefix = ObjectWorkStore.JOURNAL_PREFIX)
    }

    @AfterTest
    fun closeDrivers() = drivers.forEach { it.close() }
}
