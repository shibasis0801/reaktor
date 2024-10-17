package dev.shibasis.reaktor.auth.vertx


import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.jetbrains.exposed.sql.Database

internal fun Vertx.createRoleRouter(database: Database) = Router.router(this).apply {

}