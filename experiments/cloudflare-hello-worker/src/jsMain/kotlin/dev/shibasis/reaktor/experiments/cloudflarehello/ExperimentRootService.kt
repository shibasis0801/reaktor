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
                    "/files/runtime",
                    "/files/r2",
                    "/chat",
                    "/supabase",
                    "/supabase/status",
                    "/chat/rooms",
                    "/chat/media",
                    "/chat/rooms/{roomId}/messages",
                ),
                flow = listOf(
                    "GET /files/runtime to probe the Worker Web File System API scratch space",
                    "GET /files/r2 to probe durable file storage through the R2-backed FileAdapter",
                    "GET /supabase/status to query bestbuds-backed Supabase metadata through Hyperdrive and postgres.js",
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
