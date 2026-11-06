package dev.shibasis.reaktor.work

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlin.test.AfterTest

class AndroidSqliteWorkConformanceTest : WorkConformance() {
    private val drivers = mutableListOf<AndroidSqliteDriver>()

    override suspend fun database(): ObjectDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val driver = AndroidSqliteDriver(NoSchema, context, name = null).also { drivers += it }
        return SqliteObjectDatabase(driver, "work", journalStorePrefix = ObjectWorkStore.JOURNAL_PREFIX)
    }

    @AfterTest
    fun closeDrivers() = drivers.forEach { it.close() }
}

private object NoSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 1L
    override fun create(driver: SqlDriver) = QueryResult.Unit
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Unit
}
