package dev.shibasis.reaktor.auth.permissions


import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.jetbrains.exposed.sql.Database

internal fun Vertx.createPermissionRouter(database: Database) = Router.router(this).apply {

}