package dev.shibasis.reaktor.db.adapters

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.sql.Statement
import dev.shibasis.reaktor.io.adapters.File
import dev.shibasis.reaktor.io.adapters.FileAdapter
import kotlin.js.JsExport

@JsExport
abstract class SqlAdapter<Controller>(
    controller: Controller,
    val dbName: String = "reaktor.db",
    val fileAdapter: FileAdapter<*> = Feature.File ?: throw Error("FileAdapter not initialized")
): Adapter<Controller>(controller) {
    private var driver: SqlDriver? = null
    private var _transacter: Transacter? = null

    protected abstract fun createDriver(): SqlDriver

    fun getDriver(): SqlDriver {
        if (driver == null) {
            driver = createDriver()
        }
        return driver!!
    }

    private fun getTransacter(): Transacter {
        if (_transacter == null) {
            _transacter = object : TransacterImpl(getDriver()) {}
        }
        return _transacter!!
    }

    fun closeDriver() {
        driver?.close()
        driver = null
        _transacter = null
    }

    fun <T> transaction(body: () -> T): T {
        return getTransacter().transactionWithResult {
            body()
        }
    }

    fun execute(statement: Statement): Long {
        val result = statement.renderSql()
        val args = statement.renderArgs()
        return getDriver().execute(null, result, args.size) {
            bindArgs(this, args)
        }.value
    }

    fun executeRaw(sql: String, args: Array<Any?>): Long {
        return getDriver().execute(null, sql, args.size) {
            bindArgs(this, args)
        }.value
    }

    private fun bindArgs(binder: SqlPreparedStatement, args: Array<Any?>) {
        args.forEachIndexed { index, arg ->
            val idx = index + 1
            when (arg) {
                is Long -> binder.bindLong(idx, arg)
                is Int -> binder.bindLong(idx, arg.toLong())
                is String -> binder.bindString(idx, arg)
                is Boolean -> binder.bindLong(idx, if (arg) 1L else 0L)
                is Double -> binder.bindDouble(idx, arg)
                is ByteArray -> binder.bindBytes(idx, arg)
                null -> binder.bindBytes(idx, null)
                else -> throw IllegalArgumentException("Unsupported type: ${arg::class}")
            }
        }
    }

    fun checkSize(): Long {
        val pageCount = getDriver().executeQuery(null, "PRAGMA page_count", {
            it.next()
            QueryResult.Value(it.getLong(0) ?: 0L)
        }, 0).value

        val pageSize = getDriver().executeQuery(null, "PRAGMA page_size", {
            it.next()
            QueryResult.Value(it.getLong(0) ?: 0L)
        }, 0).value

        return pageCount * pageSize
    }

    fun vacuum() {
        getDriver().execute(null, "VACUUM", 0)
    }

    fun backup(backupName: String) {
        val backupPath = fileAdapter.resolvePath(backupName)

        fileAdapter.delete(backupPath)

        getDriver().execute(null, "VACUUM INTO ?", 1) {
            bindString(1, backupPath)
        }
    }

    fun restore(backupName: String) {
        closeDriver()

        val backupPath = fileAdapter.resolvePath(backupName)
        val dbPath = fileAdapter.resolvePath(dbName)

        if (!fileAdapter.exists(backupPath)) {
            throw Error("Backup file not found at $backupPath")
        }

        fileAdapter.delete(dbPath)
        fileAdapter.copy(backupPath, dbPath)

        getDriver()
    }
}

var Feature.Sql by CreateSlot<SqlAdapter<*>>()