package dev.shibasis.reaktor.auth.entities

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.jetbrains.exposed.sql.Database

internal fun Vertx.createEntityRouter(database: Database) = Router.router(this).apply {

}