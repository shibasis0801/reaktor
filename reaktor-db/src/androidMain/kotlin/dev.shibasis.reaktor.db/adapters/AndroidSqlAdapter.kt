package dev.shibasis.reaktor.db.adapters

import androidx.activity.ComponentActivity
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.shibasis.reaktor.db.sql.ReaktorSqliteHelper

/**
 * [onUnreadable] is told when SQLite found the file corrupt and the platform replaced it — at open,
 * or at whichever later query first reached the damage. By then the old contents are gone; this is
 * the only notice anything gets.
 */
class AndroidSqlAdapter(
    activity: ComponentActivity,
    private val onUnreadable: () -> Unit = {},
): SqlAdapter<ComponentActivity>(activity) {
    override fun createDriver(): SqlDriver = invoke {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(this)
                .name("reaktor.db")
                .callback(ReaktorSqliteHelper(1, onUnreadable))
                .build()
        )

        AndroidSqliteDriver(helper)
    } ?: throw Error("Android Sqlite Initialization Error")
}