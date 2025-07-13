package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.db.SqlDriver
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature


abstract class SqlAdapter<Controller>(
    controller: Controller
): Adapter<Controller>(controller) {
    abstract fun getDriver(): SqlDriver

    // Add / implement others as needed
    fun getDbFile() {}
    fun checkSize() {}
    fun restoreFromFile() {}
    fun useCache() {}
}

var Feature.Sqlite by CreateSlot<SqlAdapter<*>>()
