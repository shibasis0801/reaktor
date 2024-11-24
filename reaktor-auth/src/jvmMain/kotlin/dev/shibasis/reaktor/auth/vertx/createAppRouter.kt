package dev.shibasis.reaktor.auth.vertx

import dev.shibasis.reaktor.auth.apps.AppService
import dev.shibasis.reaktor.core.actor.handle
import dev.shibasis.reaktor.core.network.JsonResponse
import dev.shibasis.reaktor.core.network.toResponse
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database

internal fun Vertx.createAppRouter(database: Database) = Router.router(this).apply {
    val appService = AppService(database)

//                post(Auth.PATH).handle { ctx ->
//                val request = ctx.requestBody<Auth.Request>()
//                val idToken = request.googleUser.idToken
//                val token = verifier.verify(idToken)
//                if (token != null) {
//                    JsonResponse(request)
//                } else {
//                    ErrorResponse(1, "INVALID ID TOKEN")
//                }
//            }

    get("/login").handle {
        JsonResponse(mapOf(1 to 2))
    }

    listOf(get(), get("/")).forEach { it.handle { _ -> appService.getApps().toResponse() } }

    get("/:id").handle { ctx ->
        val id = ctx.pathParam("id").toLong()
        appService.getApp(id).toResponse()
    }

    post("/").handle { ctx ->
        val body = ctx.bodyAsJson
        val name = body.getString("name")
        val data = body.getJsonObject("data").toString()

        appService.createApp(name, Json.decodeFromString<JsonElement>(data)).toResponse()
    }

    delete("/:id").handle { ctx ->
        val id = ctx.pathParam("id").toLong()
        appService.deleteApp(id).toResponse()
    }

    put("/:id").handle { ctx ->
        val id = ctx.pathParam("id").toLong()
        val body = ctx.bodyAsJson
        val name = body.getString("name")
        val data = body.getJsonObject("data").toString()

        appService.updateApp(id, name, data).toResponse()
    }
}