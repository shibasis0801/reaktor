package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

private const val DATABASE_NAME = "reaktor.db"

class DarwinSqlAdapter: SqlAdapter<Unit>(Unit) {
    override fun createDriver(): SqlDriver {
        // Accepted intent cannot be erased because an open failed (including transient lock errors).
        return openDriver()
    }

    private fun openDriver(): SqlDriver {
        val driver = NativeSqliteDriver(DatabaseConfiguration(DATABASE_NAME, 1, {}), 1)
        try {
            // Connections open lazily, so read the schema page now. Left to itself, a corrupt
            // file would surface at some later query, far from the only place that can still
            // choose to start over.
            driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM sqlite_master",
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 0,
            ).value
        } catch (throwable: Throwable) {
            runCatching { driver.close() }
            throw throwable
        }
        return driver
    }
}
