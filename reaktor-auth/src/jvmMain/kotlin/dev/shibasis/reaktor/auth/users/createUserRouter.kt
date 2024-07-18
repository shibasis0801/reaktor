package dev.shibasis.reaktor.auth.users


import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.jetbrains.exposed.sql.Database

internal fun Vertx.createUserRouter(database: Database) = Router.router(this).apply {

}