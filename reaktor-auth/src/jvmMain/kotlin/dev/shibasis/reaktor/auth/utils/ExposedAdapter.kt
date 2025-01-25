package dev.shibasis.reaktor.auth.utils

import dev.shibasis.reaktor.core.framework.Adapter
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

fun<T> succeed(value: T) = Result.success(value)
fun<T> fail(message: String) = Result.failure<T>(Throwable(message))
fun<T> fail(throwable: Throwable) = Result.failure<T>(throwable)

open class ExposedAdapter(
    database: Database
): Adapter<Database>(database) {
    protected fun <T> sql(statement: () -> T?): Result<T> = invoke {
        try {
            transaction(this) {
                val data = statement()
                if (data != null) {
                    if (data is Throwable) {
                        fail(data)
                    } else {
                        succeed(data)
                    }
                }
                else
                    fail(NullPointerException("Not Found"))
            }
        } catch (e: Exception) {
            fail(e)
        }
    } ?: fail("Database Initialization Error")
}
