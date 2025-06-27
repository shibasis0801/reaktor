package dev.shibasis.reaktor.framework

import dev.shibasis.reaktor.io.service.BaseRequest

abstract class CrudRepository(
    val adapter: ExposedAdapter
) {
    suspend fun<T> BaseRequest.sql(
        statement: () -> T?
    ): Result<T> = adapter.sql(environment, statement)
}

