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
     * The database handle for [environment]. Any code opening its own transaction must resolve
     * through this — a bare `transaction { }` binds to Exposed's environment-blind default.
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
