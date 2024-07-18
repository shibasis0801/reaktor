package dev.shibasis.reaktor.auth.apps

import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.core.actor.handle
import dev.shibasis.reaktor.core.network.ErrorResponse
import dev.shibasis.reaktor.core.network.JsonResponse
import dev.shibasis.reaktor.core.network.StatusCode
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.deleteWhere


internal fun Vertx.createAppRouter(database: Database) = Router.router(this).apply {
    get("/").handle { _, _ ->
        transaction(database) {
            JsonResponse(
                Apps.selectAll().map {
                    it[Apps.name]
                }
            )
        }
    }

    get("/:id").handle { ctx, _ ->
        val id = ctx.pathParam("id").toLong()
        transaction(database) {
            val app = Apps.selectAll().where { Apps.id eq id }.singleOrNull()
            if (app == null) {
                ErrorResponse(1, "App not found", StatusCode.NOT_FOUND)
            } else {
                JsonResponse(app[Apps.name])
            }
        }
    }

    post("/").handle { ctx, _ ->
        val body = ctx.body().asJsonObject()
        val name = body.getString("name")
        val data = body.getJsonObject("data").toString()

        transaction(database) {
            Apps.insert {
                it[Apps.name] = name
                it[Apps.data] = Json.decodeFromString<JsonElement>(data)
            }
        }

        JsonResponse("App created")
    }

    put("/:id").handle { ctx, _ ->
        val id = ctx.pathParam("id").toLong()
        val body = ctx.body().asJsonObject()
        val name = body.getString("name")
        val data = body.getJsonObject("data").toString()

        transaction(database) {
            val app = Apps.selectAll().where { Apps.id eq id }.singleOrNull()
            if (app == null) {
                ErrorResponse(1, "App not found", StatusCode.NOT_FOUND)
            } else {
                Apps.update({ Apps.id eq id }) {
                    it[Apps.name] = name
                    it[Apps.data] = Json.decodeFromString<JsonElement>(data)
                }
                JsonResponse("App updated")
            }
        }
    }

    delete("/:id").handle { ctx, _ ->
        val id = ctx.pathParam("id").toLong()
        transaction(database) {
            val app = Apps.selectAll().where { Apps.id eq id }.singleOrNull()
            if (app == null) {
                ErrorResponse(1, "App not found", StatusCode.NOT_FOUND)
            } else {
                Apps.deleteWhere { Apps.id eq id }
                JsonResponse("App deleted")
            }
        }
    }
}