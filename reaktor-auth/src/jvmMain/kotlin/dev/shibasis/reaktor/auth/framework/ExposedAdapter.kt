package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.core.framework.Adapter
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
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


inline fun LocalDateTime.Companion.now() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

fun<T: Comparable<T>> IdTable<T>.entityId(id: T) = EntityID(id, this)
