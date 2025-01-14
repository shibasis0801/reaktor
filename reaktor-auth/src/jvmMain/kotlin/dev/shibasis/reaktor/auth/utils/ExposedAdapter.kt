package dev.shibasis.reaktor.auth.utils

import dev.shibasis.reaktor.core.framework.Adapter
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

fun<T> succeed(value: T) = Result.success(value)
fun<T> fail(message: String) = Result.failure<T>(Throwable(message))

open class ExposedAdapter(
    database: Database
): Adapter<Database>(database) {
    protected fun <T> sql(statement: () -> T?): Result<T> = invoke {
        try {
            transaction(this) {
                val data = statement()
                if (data != null)
                    succeed(data)
                else
                    Result.failure(NullPointerException("Not Found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: Result.failure(Error("Database Initialization Error"))
}
