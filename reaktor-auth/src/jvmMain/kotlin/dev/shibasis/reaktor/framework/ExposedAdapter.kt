package dev.shibasis.reaktor.framework

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.graph.service.Environment
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
    fun npe() = NullPointerException("Not Found")
    private val dbDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    suspend fun <T> sql(
        environment: Environment,
        statement: () -> T?
    ): Result<T> {
        // improve later
        return runCatching {
            val db = if (environment == Environment.PROD) prodDb else stageDb
            GlobalScope.async(dbDispatcher) {
                transaction(db) {
                    exec("SET search_path TO heimdall, public;")
                    val data = statement() ?: throw npe()
                    if (data is Throwable) throw data

                    data
                }
            }.await()
        }
    }
}

