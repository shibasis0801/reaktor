package dev.shibasis.reaktor.graph.service.db

import dev.shibasis.reaktor.graph.service.Request

abstract class CrudRepository(
    val adapter: ExposedAdapter
) {
    suspend fun<T> Request.sql(
        statement: () -> T?
    ): Result<T> = adapter.sql(environment, statement)
}
