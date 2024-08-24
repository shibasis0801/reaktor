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
    val appService = AppService(database)
// need to convert a result into a response
//    get("/").handle { _, _ -> appService.getApps() }
//
//    get("/:id").handle { ctx, _ ->
//        val id = ctx.pathParam("id").toLong()
//        appService.getApp(id)
//    }
//
//    post("/").handle { ctx, _ ->
//        val body = ctx.body().asJsonObject()
//        val name = body.getString("name")
//        val data = body.getJsonObject("data").toString()
//        appService.createApp(name, data)
//    }
//
//    put("/:id").handle { ctx, _ ->
//        val id = ctx.pathParam("id").toLong()
//        val body = ctx.body().asJsonObject()
//        val name = body.getString("name")
//        val data = body.getJsonObject("data").toString()
//        appService.updateApp(id, name, data)
//    }
//
//    delete("/:id").handle { ctx, _ ->
//        val id = ctx.pathParam("id").toLong()
//        appService.deleteApp(id)
//    }
}
