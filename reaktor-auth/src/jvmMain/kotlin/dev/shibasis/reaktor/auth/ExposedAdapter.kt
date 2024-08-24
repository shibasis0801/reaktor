package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.network.ErrorResponse
import dev.shibasis.reaktor.core.network.Response
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

object ResultHelper {
    fun <T> success(value: T): Result<T> = Result.success(value)
    fun <T> failure(message: String): Result<T> = Result.failure(Error(message))
}

open class ExposedAdapter(
    database: Database
): Adapter<Database>(database) {
    protected fun <T> sql(statement: ResultHelper.() -> Result<T>): Result<T> = invoke {
        transaction(this) {
            statement()
        }
    } ?: Result.failure(Error("Database Initialization Error"))
}
