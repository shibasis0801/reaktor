package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object Checklist {
    val ClaudeTools = setOf("TodoWrite", "mcp__ccd_turn__update_plan")

    fun claude(tool: String, input: JsonObject, current: List<PlanItem>): List<PlanItem>? = when (tool) {
        "TodoWrite" -> input.list("todos").mapNotNull { it as? JsonObject }.map { PlanItem(it.str("content").orEmpty(), state(it.str("status"))) }
        "mcp__ccd_turn__update_plan" -> steer(input, current)
        else -> null
    }

    fun codex(arguments: JsonObject): List<PlanItem> =
        arguments.list("plan").mapNotNull { it as? JsonObject }.map { PlanItem(it.str("step").orEmpty(), state(it.str("status"))) }

    private fun steer(input: JsonObject, current: List<PlanItem>): List<PlanItem> {
        var plan = input.list("items").mapNotNull { it as? JsonObject }.map { PlanItem(it.str("content").orEmpty(), state(it.str("status"))) }.ifEmpty { current }
        if (input.flag("advance")) {
            val active = plan.indexOfFirst { it.state == PlanState.Active }
            plan = plan.mapIndexed { index, item -> if (index == active) item.copy(state = PlanState.Done) else item }
            val next = plan.indexOfFirst { it.state == PlanState.Pending }
            if (next >= 0) plan = plan.mapIndexed { index, item -> if (index == next) item.copy(state = PlanState.Active) else item }
        }
        val done = input.list("done").mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet()
        val start = (input["start"] as? JsonPrimitive)?.intOrNull
        if (done.isNotEmpty() || start != null) plan = plan.mapIndexed { index, item ->
            when (index + 1) {
                in done -> item.copy(state = PlanState.Done)
                start -> item.copy(state = PlanState.Active)
                else -> item
            }
        }
        return plan
    }

    private fun state(status: String?) = when (status) {
        "completed" -> PlanState.Done
        "in_progress" -> PlanState.Active
        else -> PlanState.Pending
    }
}
