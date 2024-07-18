package dev.shibasis.reaktor.auth.apps

import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.core.actor.handle
import dev.shibasis.reaktor.core.network.JsonResponse
import dev.shibasis.reaktor.core.network.Response
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal fun Vertx.createAppRouter(database: Database) = Router.router(this).apply {
    get("/").handle { _, _ ->
        transaction {
            JsonResponse(
                Apps.selectAll().map {
                    it[Apps.name]
                }
            )
        }
    }
}