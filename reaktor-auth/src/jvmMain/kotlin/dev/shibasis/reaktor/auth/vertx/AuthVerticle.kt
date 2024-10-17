package dev.shibasis.reaktor.auth.vertx

import dev.shibasis.reaktor.core.actor.BaseVerticle
import io.vertx.ext.web.Router
import org.jetbrains.exposed.sql.Database


class AuthVerticle(
    private val database: Database
): BaseVerticle() {
    override fun createRouter(): Router? {
        return Router.router(vertx).apply {
            route("/apps/*").subRouter(vertx.createAppRouter(database))
            route("/entities/*").subRouter(vertx.createEntityRouter(database))
            route("/users/*").subRouter(vertx.createUserRouter(database))
            route("/roles/*").subRouter(vertx.createRoleRouter(database))
            route("/permissions/*").subRouter(vertx.createPermissionRouter(database))
        }
    }
}