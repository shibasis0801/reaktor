package dev.shibasis.reaktor.work

import app.cash.sqldelight.driver.worker.WebWorkerDriver
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import org.w3c.dom.Worker
import kotlin.test.AfterTest

class WebSqliteWorkConformanceTest : WorkConformance() {
    private val drivers = mutableListOf<WebWorkerDriver>()

    override suspend fun database(): ObjectDatabase {
        val driver = WebWorkerDriver(NodeSqliteWorker().unsafeCast<Worker>()).also { drivers += it }
        return SqliteObjectDatabase(driver, "work", journalStorePrefix = ObjectWorkStore.JOURNAL_PREFIX)
    }

    @AfterTest
    fun closeDrivers() = drivers.forEach { it.close() }
}
