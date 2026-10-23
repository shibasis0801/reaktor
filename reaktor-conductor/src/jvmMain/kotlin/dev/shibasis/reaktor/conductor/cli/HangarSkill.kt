package dev.shibasis.reaktor.conductor.cli

import java.io.File

object HangarSkill {
    const val Name = "hangar"

    enum class Vendor(val call: String, val front: List<String>, val request: String, val lead: String) {
        Claude("/hangar", listOf("disable-model-invocation: true", "argument-hint: \"[here] <request>\""),
            "The request: \$ARGUMENTS",
            "- `lead`: `\"claude:\${CLAUDE_SESSION_ID}\"`, this session."),
        Codex("\$hangar", emptyList(),
            "The request is what the person wrote after `\$hangar`.",
            "- Leave out `lead`: Hangar names this session as the lead, because it can tell which Codex session is calling."),
        Antigravity("/hangar", emptyList(),
            "The request is what the person wrote after `/hangar`.",
            "- Leave out `lead`, and give every task to an agent: Hangar cannot yet tell which Antigravity conversation is calling, so `/hangar here` is not available here."),
        Prompt("hangar", emptyList(),
            "The request: @request@",
            "- `lead`: this session as `claude:<session>`, `codex:<session>` or `hangar:<conversation>` when you know it; otherwise leave it out."),
    }

    fun skill(vendor: Vendor): String = buildString {
        appendLine("---")
        appendLine("name: $Name")
        appendLine("description: Plans a request as a task graph that Hangar runs with several agents, then hands it to the person to change and approve. Use only when the person types ${vendor.call} with a request.")
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
        |  display_name: "Hangar task graph"
        |  short_description: "Plan a request as a task graph that Hangar runs"
        |policy:
        |  allow_implicit_invocation: false
        |""".trimMargin()

    private fun body(vendor: Vendor): String = """
        |# ${vendor.call}: plan the request as a task graph
        |
        |${vendor.request}
        |
        |Hangar runs a task graph: a few board tasks joined by dependencies, each done by the agent it names. You propose the graph, the person changes and approves it in Hangar, Hangar's agents do the tasks, and you stay its lead. `${vendor.call} here <request>` means you also do some of the tasks yourself, in this session.
        |
        |Every call below goes through the reaktor MCP server's `kernel` tool as `{"operation": "<name>", "arguments": {...}}`. If there is no reaktor server, say so and stop: this folder is not a Reaktor workspace.
        |
        |## 1. Read, briefly
        |
        |- `atlas_query` and `atlas_read` on the code the request touches: enough to name the modules each task changes and the order the work goes in.
        |- `board.list` for the open board tasks. Leave out work that is already there.
        |- `taskgraph.seats` for the agents, models and efforts this workspace runs.
        |
        |## 2. Propose
        |
        |Call `taskgraph.propose` with:
        |
        |- `title`, the request in a few words, and `goal`, what done looks like.
        |- `tasks`: 2 to 12 tasks in order, each `{"key", "title", "intent", "acceptance", "scope", "seat", "dependsOn"}`.
        |  - `key` is a short name that later tasks use in `dependsOn`. `scope` lists the modules the task may change, such as `:engine`.
        |  - `seat` is `{"agent": "Codex" | "ClaudeCode" | "Gemini", "access": "Write" | "Read"}`, with `model` and `effort` when the work needs them. Only tasks that change code get `Write`.
        |  - `dependsOn` is `[{"task": "<key>", "condition": "Succeeded"}]`. The other conditions are `Failed`, `Always`, `Pass`, `Repair` and `Accepted`.
        |  - A review task has `"contract": "Decision"` and ends with a verdict, pass or repair. The task after it waits on `Pass`. When a review asks for a repair, Hangar adds the repair and a second review by itself.
        |  - `{"kind": "Check", "check": "<check id>"}` runs one check on the work of the task it depends on.
        |  - `{"kind": "Gate"}` waits for the person. Put one before anything lands: landing in the main checkout, pushing, pull requests, deploys and production writes are the person's.
        |  - For `${vendor.call} here`, the tasks you keep get the seat `{"lead": true}`.
        |${vendor.lead}
        |- `envelope`, only if the defaults do not fit: what agents may change later without asking, `{"agents", "models", "efforts", "writes", "maxTasks", "tokens", "repairRounds"}`. By default it covers the agents and modules you proposed.
        |
        |## 3. Hand it over
        |
        |Tell the person the graph's id, its tasks and who does each, and that it waits for them in Hangar under Agent, Work, Graphs. Do not start it: only a person approves and starts a graph. Then end your turn.
        |
        |## 4. While it runs
        |
        |- `taskgraph.read {"graph": "G1"}` shows each task's status and outcome, and the graph's revision.
        |- `taskgraph.patch {"graph": "G1", "expectedRevision": <the revision you read>, "reason": "<why>", "ops": [...]}` changes the graph as you learn more, without asking again. The ops are `{"type": "add", "title", "key", "dependsOn", "seat", ...}`, `{"type": "update", "task": "T3", ...}`, `{"type": "split", "task": "T3", "into": [{"title", ...}]}`, `{"type": "remove", "task": "T3"}`, `{"type": "connect", "from": "T2", "to": "T3", "condition": "Succeeded"}`, `{"type": "disconnect", "from", "to"}`, `{"type": "seat", "task", "seat"}` and `{"type": "note", "text"}`. The person sees each change with its reason.
        |- `taskgraph.pause {"graph": "G1", "reason": "<why>"}` lets running tasks finish and starts no new ones.
        |- With `${vendor.call} here`, when one of your tasks is ready, call `taskgraph.claim {"graph", "task"}`, do the work in this session, then `taskgraph.report {"graph", "task", "status": "Succeeded" | "Failed" | "NeedsInput" | "Blocked", "summary", "files"}`. When none of your tasks is ready, end your turn. Hangar tells the person when the next one is.
        |- `session.log {"session": "<the task's session>"}` reads what another task's agent did.
        |""".trimMargin()
}
