package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn

private const val DATABASE_NAME = "reaktor.db"

class DarwinSqlAdapter: SqlAdapter<Unit>(Unit) {
    private val log = "DarwinSqlAdapter".logger()

    override fun createDriver(): SqlDriver {
        return try {
            openDriver()
        } catch (throwable: Throwable) {
            // A file SQLite refuses to open cannot be repaired from here, and the alternative to
            // replacing it is an app that throws on every launch until it is deleted — which
            // costs the user the same data plus the app. Android's SQLite already deletes and
            // recreates a corrupt file; this keeps the two platforms behaving alike.
            log.warn { "Recreating $DATABASE_NAME, which would not open: ${throwable.message}" }
            DatabaseFileContext.deleteDatabase(DATABASE_NAME)
            openDriver()
        }
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
