package dev.shibasis.reaktor.conductor.cli

import java.io.File

object HangarSkill {
    const val Name = "hangar"

    enum class Vendor(val call: String, val front: List<String>, val request: String, val thread: String) {
        Claude("/hangar", listOf("disable-model-invocation: true", "argument-hint: \"[here] <request>\""),
            "The request: \$ARGUMENTS",
            "- The plan belongs to this session, `\"claude:\${CLAUDE_SESSION_ID}\"`. Pass it as `thread` when a call asks which thread."),
        Codex("\$hangar", emptyList(),
            "The request is what the person wrote after `\$hangar`.",
            "- Leave out `thread`: Hangar names this session's plan, because it can tell which Codex session is calling."),
        Antigravity("/hangar", emptyList(),
            "The request is what the person wrote after `/hangar`.",
            "- Pass `thread` as `\"agy:<this conversation>\"`: Hangar cannot yet tell which Antigravity conversation is calling."),
        Prompt("hangar", emptyList(),
            "The request: @request@",
            "- `thread`: this session as `claude:<session>`, `codex:<session>` or `hangar:<conversation>` when you know it; otherwise leave it out."),
    }

    fun skill(vendor: Vendor): String = buildString {
        appendLine("---")
        appendLine("name: $Name")
        appendLine("description: Draws a request as a Hangar plan that the person and several agents work through together, then hands it to the person to change and staff. Use only when the person types ${vendor.call} with a request.")
        vendor.front.forEach(::appendLine)
        appendLine("---")
        appendLine()
        append(body(vendor))
    }

    fun prompt(request: String): String = body(Vendor.Prompt).replace("@request@", request.trim())

    fun files(home: File): Map<String, File> = mapOf(
        "claude" to File(home, ".claude/skills/$Name/SKILL.md"),
        "codex" to File(home, ".agents/skills/$Name/SKILL.md"),
        "codex-agent" to File(home, ".agents/skills/$Name/agents/openai.yaml"),
        "antigravity" to File(home, ".gemini/config/skills/$Name/SKILL.md"),
    )

    fun content(key: String): String = when (key) {
        "claude" -> skill(Vendor.Claude)
        "codex" -> skill(Vendor.Codex)
        "codex-agent" -> CodexAgent
        "antigravity" -> skill(Vendor.Antigravity)
        else -> error("No hangar skill file named $key")
    }

    private val CodexAgent = """
        |interface:
        |  display_name: "Hangar plan"
        |  short_description: "Plan a request in Hangar for several agents"
        |policy:
        |  allow_implicit_invocation: false
        |""".trimMargin()

    private fun body(vendor: Vendor): String = """
        |# ${vendor.call}: plan the request in Hangar
        |
        |${vendor.request}
        |
        |Hangar shows each thread's plan: the high-level design of the work as items with a status, what each waits on, what it belongs to and what it touches in Reaktor. The person and the agents edit one plan, in Hangar's DAG, Kanban and Graph views or with `plan.write`. The person hands parts of it, an item and everything under it, to gallery agents, and each agent changes only its part. `${vendor.call} here <request>` means you also work on items yourself, in this session.
        |
        |Every call below goes through the reaktor MCP server's `kernel` tool as `{"operation": "<name>", "arguments": {...}}`. If there is no reaktor server, say so and stop: this folder is not a Reaktor workspace.
        |
        |## 1. Read, briefly
        |
        |- `atlas_query` and `atlas_read` on the code the request touches: enough to name the modules each part changes and the order the work goes in.
        |- `plan.list` for the plans already drawn. Leave out work another plan already covers.
        |- `taskgraph.seats` for the agents, models, efforts and saved profiles this workspace runs. The gallery agents a person attaches are these profiles.
        |
        |## 2. Draw the plan
        |
        |Call `plan.write` with `title`, the request in a few words, `goal`, what done looks like, and `ops`:
        |
        |- `{"type": "add", "key", "title", "detail", "after": ["<key>"], "parent": "<key>", "refs": ["<link>"]}` for each item, in order.
        |  - `key` is a short name that later items use in `after` and `parent`.
        |  - `parent` makes an item part of a larger one. Size each top-level part so one agent can take it with everything under it.
        |  - `refs` link the item into Reaktor: `file:<path>`, `symbol:<Type.member>`, `module:<:path>`, `graph:<name>`, `op:<operation>`, `flow:<id>`, `task:<id>` or `url:<link>`.
        |- Keep landing, pushing, pull requests, deploys and production writes as items for the person.
        |${vendor.thread}
        |
        |## 3. Hand it over
        |
        |Tell the person the plan's title and its parts, and that it waits for them in Hangar under Agent, Work, on this thread. There they change it and attach gallery agents to parts with Attach agent; only a person attaches agents. Then end your turn, unless the request was `${vendor.call} here`: then work on the items you keep, as below.
        |
        |## 4. While the work goes on
        |
        |- Keep statuses current with `{"type": "status", "item": "<key>", "status": "Doing" | "Done" | "Dropped"}` as you start and finish items, and add what you discover as new items.
        |- Pass `base`, the revision you last read. An op on an item someone else changed after it is refused for that item only, and the answer says who changed what.
        |- `plan.read {"since": <revision>}` lists what the person and the other agents changed. Follow those changes before you go on.
        |- An agent attached to a part passes its `seat` and changes only that part.
        |- `session.log {"session": "<an agent's session>"}` reads what another agent did.
        |""".trimMargin()
}
