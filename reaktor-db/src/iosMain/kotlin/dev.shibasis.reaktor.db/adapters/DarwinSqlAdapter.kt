package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

class DarwinSqlAdapter: SqlAdapter<Unit>(Unit) {
    override fun getDriver(): SqlDriver {
        return NativeSqliteDriver(DatabaseConfiguration(
            "reaktor.db",
            1,
            {}
        ), 1)
    }
}