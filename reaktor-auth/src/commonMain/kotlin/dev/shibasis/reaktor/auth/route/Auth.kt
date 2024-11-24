package dev.shibasis.reaktor.auth.route

import dev.shibasis.reaktor.auth.GoogleUser
import kotlinx.serialization.Serializable

object Auth {
    const val PATH = "/login"

    @Serializable
    data class Request(val googleUser: GoogleUser)

    @Serializable
    data class Response(val googleUser: GoogleUser)
}