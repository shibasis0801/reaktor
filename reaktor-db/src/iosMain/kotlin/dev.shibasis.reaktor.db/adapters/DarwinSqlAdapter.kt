package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseManager

class DarwinSqlAdapter: SqlAdapter<Unit>(Unit) {
    override fun getDriver(): SqlDriver {
        return NativeSqliteDriver(DatabaseConfiguration(
            "",
            1,
            {}
        ), 1)
    }
}