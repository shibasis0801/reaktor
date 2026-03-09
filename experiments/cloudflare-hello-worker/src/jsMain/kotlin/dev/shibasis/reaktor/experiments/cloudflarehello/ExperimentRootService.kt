package dev.shibasis.reaktor.experiments.cloudflarehello

import dev.shibasis.reaktor.experiments.cloudflarehello.chat.ChatOverviewRequest
import dev.shibasis.reaktor.experiments.cloudflarehello.chat.ChatOverviewResponse
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.Service

class ExperimentRootService : Service() {
    init {
        GetHandler<ChatOverviewRequest, ChatOverviewResponse>("/") {
            ChatOverviewResponse(
                message = "Cloudflare chat experiment powered by Kotlin services",
                routes = listOf(
                    "/chat",
                    "/chat/rooms",
                    "/chat/media",
                    "/chat/rooms/{roomId}/messages",
                ),
                flow = listOf(
                    "POST /chat/rooms to create a room",
                    "POST /chat/rooms/{roomId}/members to coordinate presence via Durable Objects",
                    "POST /chat/media to store attachments in R2",
                    "POST /chat/rooms/{roomId}/messages to persist chat history in D1",
                    "GET /chat/rooms/{roomId}/messages to replay persisted history",
                ),
            )
        }
    }
}
