package dev.shibasis.reaktor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shibasis.reaktor.database.PrimaryDatabase

class Greeting {
    private val platform: Platform = getPlatform()

    fun greet(): String {
        return "Hello, ${platform.name}!"
    }

}

abstract class Database<Controller>(
    protected val controller: Controller,
    protected val name: String = "mehmaan.db"
) {
    abstract suspend fun getDriver(schema: SqlSchema<QueryResult.AsyncValue<Unit>>): SqlDriver
    suspend fun getDb() = PrimaryDatabase(getDriver(PrimaryDatabase.Schema))

    suspend fun test() {
        val x = getDb()
    }
}

