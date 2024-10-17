package dev.shibasis.reaktor.auth.vertx

import dev.shibasis.reaktor.auth.apps.AppService
import dev.shibasis.reaktor.core.actor.handle
import dev.shibasis.reaktor.core.network.toResponse
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database

internal fun Vertx.createAppRouter(database: Database) = Router.router(this).apply {
    val appService = AppService(database)

    listOf(get(), get("/")).forEach { it.handle { _, _ -> appService.getApps().toResponse() } }

    get("/:id").handle { ctx, _ ->
        val id = ctx.pathParam("id").toLong()
        appService.getApp(id).toResponse()
    }

    post("/").handle { ctx, _ ->
        val body = ctx.bodyAsJson
        val name = body.getString("name")
        val data = body.getJsonObject("data").toString()

        appService.createApp(name, Json.decodeFromString<JsonElement>(data)).toResponse()
    }

    delete("/:id").handle { ctx, _ ->
        val id = ctx.pathParam("id").toLong()
        appService.deleteApp(id).toResponse()
    }

    put("/:id").handle { ctx, _ ->
        val id = ctx.pathParam("id").toLong()
        val body = ctx.bodyAsJson
        val name = body.getString("name")
        val data = body.getJsonObject("data").toString()

        appService.updateApp(id, name, data).toResponse()
    }
}