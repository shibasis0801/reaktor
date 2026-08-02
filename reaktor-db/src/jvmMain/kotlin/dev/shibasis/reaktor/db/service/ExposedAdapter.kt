package dev.shibasis.reaktor.db.service

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.Executors

open class ExposedAdapter(
    val stageDb: Database,
    val prodDb: Database
): Adapter<Unit>(Unit) {
    private val dbDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * The database handle for [environment].
     *
     * Exposed's bare `transaction { }` binds to a *default* database (the registry's most recent
     * connect), which is environment-blind. That was harmless while stageDb and prodDb were the
     * same instance, but once they differ any bare transaction silently reads/writes the wrong
     * tier. Code outside [sql] that opens its own transaction must resolve its database through
     * this, so the tier is always explicit.
     */
    fun databaseFor(environment: Environment): Database =
        if (environment == Environment.PROD) prodDb else stageDb

    suspend fun <T> sql(
        environment: Environment,
        statement: () -> T?
    ): Result<T> {
        // improve later
        return runCatching {
            val db = databaseFor(environment)
            GlobalScope.async(dbDispatcher) {
                transaction(db) {
                    exec("SET search_path TO heimdall, public;")
                    val data = statement() ?: throw NullPointerException("Not Found")
                    if (data is Throwable) throw data

                    data
                }
            }.await()
        }
    }
}
