package dev.shibasis.reaktor.db.adapters

import androidx.activity.ComponentActivity
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.shibasis.reaktor.db.sql.ReaktorSqliteHelper

class AndroidSqlAdapter(
    activity: ComponentActivity
): SqlAdapter<ComponentActivity>(activity) {
    override fun getDriver(): SqlDriver = invoke {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(this)
                .name("reaktor.db")
                .callback(ReaktorSqliteHelper(1))
                .build()
        )

        AndroidSqliteDriver(helper)
    } ?: throw Error("Android Sqlite Initialization Error")
}