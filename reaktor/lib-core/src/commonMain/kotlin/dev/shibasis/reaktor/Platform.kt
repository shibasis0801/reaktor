package dev.shibasis.reaktor

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform