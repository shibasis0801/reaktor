package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.work.*

fun workInspectionHandler(inspector: () -> WorkInspector?): CommandHandler =
    commandHandler("work.inspect", "describe", "read") { command ->
        val host = inspector() ?: return@commandHandler AgentCommandResult(command.id, false, "Work host is not initialized")
        val payload = when (command.action) {
            "describe" -> {
                host.read(WorkQuery(host.source, limit = 1))
                json.encodeToString(WorkSource.serializer(), host.source)
            }
            else -> {
                val query = json.decodeFromString(WorkQuery.serializer(), command.arguments["query"] ?: error("Inspection query required"))
                json.encodeToString(WorkInspectionPage.serializer(), host.read(query))
            }
        }
        AgentCommandResult(command.id, true, "Scoped Work metadata inspection", payload = payload)
    }

fun workControlHandler(controller: () -> WorkControlEndpoint?): CommandHandler =
    commandHandler("work.control", "execute") { command ->
        val endpoint = controller() ?: return@commandHandler AgentCommandResult(command.id, false, "Work control unavailable")
        val request = json.decodeFromString(WorkControlCommand.serializer(), command.arguments["command"] ?: error("Work command required"))
        val receipt = endpoint.execute(request)
        AgentCommandResult(command.id, receipt.committed, if (receipt.committed) "WorkStore transition committed" else "Work precondition or state rejected",
            payload = json.encodeToString(WorkControlReceipt.serializer(), receipt))
    }
