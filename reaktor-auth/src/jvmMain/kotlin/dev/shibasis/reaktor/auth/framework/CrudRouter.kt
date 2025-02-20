package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.auth.apps.jsonResponse
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter



inline fun<ID: Comparable<ID>, reified TableEntity: Entity<ID>, TableEntityClass: EntityClass<ID, TableEntity>>
        CrudRepository<ID, TableEntity, TableEntityClass>.incompleteRouter() = coRouter {
    GET("/") {
        jsonResponse(all())
    }
    POST("/") { request ->
        val body = request.awaitBody<TableEntity>()
//        create(body)
        jsonResponse(1)
    }
    GET("/{id}") {
        val id = it.pathVariable("id")
//        jsonResponse(find(id))
        jsonResponse(1)
    }
    PUT("/{id}") { request ->
        val id = request.pathVariable("id")
        val body = request.awaitBody<TableEntity>()
//        jsonResponse(update(id, body))
        jsonResponse(1)
    }
    DELETE("/{id}") {
        val id = it.pathVariable("id")
//        jsonResponse(delete(id))
//        delete(id)
        jsonResponse(1)
    }
}