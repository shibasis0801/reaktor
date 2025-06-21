package dev.shibasis.reaktor.auth.framework

import org.springframework.web.reactive.function.server.CoRouterFunctionDsl
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse


//suspend inline fun<reified T> jsonResponse(data: T, status: HttpStatus = HttpStatus.OK) =
//    ServerResponse.status(status)
//        .contentType(MediaType.APPLICATION_JSON)
//        .bodyValueAndAwait(json.encodeToString(data))
//
//suspend inline fun<reified T> errorResponse(code: Int, data: T, status: HttpStatus = HttpStatus.BAD_REQUEST) =
//    jsonResponse(ErrorMessage(code, json.encodeToString(data)), status)
