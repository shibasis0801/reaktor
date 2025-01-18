package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.sql.*


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


// Example
object Users: Table("Users") {
    val id = column("id", IntType, primaryKey = true, notNull = true)
    val name = column("name", StringType, notNull = true)
    val age = column("age", IntType)
}

fun testDrive() {
    println(CreateTable(Users).toSql())
    println(
        Insert(Users)
            .set(Users.id, 1)
            .set(Users.name, "Alice")
            .set(Users.age, 24)
            .toSql()
    )
    println(
        Select()
            .from(Users)
            .columns(Users.id, Users.name, avg(Users.age))
            .where(Users.age gt 18)
            .groupBy(Users.name)
//            .having(count(Users.id) gt 0)
            .orderBy(Users.name, SortOrder.ASC)
            .limit(5)
            .offset(2)
            .toSql()
    )
    println(
        Update(Users)
            .set(Users.name, "Updated")
            .where(Users.id eq 1)
            .toSql()
    )
    println(
        Delete(Users)
            .where(Users.age lt 18)
            .toSql()
    )
    println(DropTable(Users).toSql())
}
