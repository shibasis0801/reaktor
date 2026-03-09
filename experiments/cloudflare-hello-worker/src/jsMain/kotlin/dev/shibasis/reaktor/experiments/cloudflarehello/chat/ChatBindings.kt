package dev.shibasis.reaktor.experiments.cloudflarehello.chat

import dev.shibasis.reaktor.cloudflare.d1
import dev.shibasis.reaktor.cloudflare.durableObject
import dev.shibasis.reaktor.cloudflare.r2

object ChatBindings {
    val database = d1("CHAT_DB")
    val media = r2("CHAT_MEDIA")
    val coordinator = durableObject("CHAT_COORDINATOR")

    const val mediaPrefix = "chat/media"
}
