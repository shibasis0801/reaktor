package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlin.test.AfterTest

class NativeSqliteWorkConformanceTest : WorkConformance() {
    private val drivers = mutableListOf<NativeSqliteDriver>()

    override suspend fun database(): ObjectDatabase {
        val configuration = DatabaseConfiguration(name = "work-${opened++}", version = 1, create = {}, inMemory = true)
        val driver = NativeSqliteDriver(configuration, 1).also { drivers += it }
        return SqliteObjectDatabase(driver, "work", journalStorePrefix = ObjectWorkStore.JOURNAL_PREFIX)
    }

    @AfterTest
    fun closeDrivers() = drivers.forEach { it.close() }
}

private var opened = 0
