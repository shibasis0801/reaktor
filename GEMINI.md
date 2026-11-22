# GEMINI.md

Read [AGENTS.md](./AGENTS.md). It is the canonical agent guide for this repo.
The product-side guide at `../bestbuds/AGENTS.md` governs the design bar and build loops.

## Peer Collaboration in Reaktor+

You are a first-class engineering peer (`gemini-4-argon-high`) alongside `claude-opus-5-5` (`max`) and `gpt-6.1-sol` (`xhigh`). Collaboration across seats is flexible:
- Pair `claude-opus-5-5` (`max`) + `gemini-4-argon-high` (`high`) to expand implementation and review capacity.
- Run the 3-seat native council (`claude-opus-5-5`, `gpt-6.1-sol`, `gemini-4-argon-high`) when independent architectural, verification, and systems lenses are needed.

## Reaktor+ Tool Workflow

Use the `reaktor` MCP server tools before shell searches or raw file edits:
1. `atlas_query` and `atlas_read` to inspect declarations, callers, build modules, and graph wiring.
2. `change_plan` and `change_apply` to stage and write changes with atomic receipts.
3. `check_run` to run narrowed affected test classes through the code graph.
4. `kernel` (`plan.read`, `plan.write`, `plan.next`, `plan.archive`, `team.send`, `team.inbox`) to coordinate on Hangar plans and peer threads.

## Quota & Token Efficiency

Preserve high-tier Gemini quota (`Carbon` / `Argon`) during multi-step work:
- **Prefer graph-bounded reads and batched edits over raw file loops:** Use `code.investigate` (`tool.workspace`) for definitions + references + affected files in one call, batch up to 16 files in `atlas_read {paths: [...]}`, batch up to 64 edits or cross-module refactors in `change_plan` (`edit` / `rename` / `structural` / `change-type`), and run `check_run` (narrow by default) instead of full Gradle suites via `run_command`.
- **Use lighter models for `research` subagents:** Pass `Model: "flash"` (or `"flash_lite"`) to `invoke_subagent` for exploration so subagents do not inherit `Carbon (High)` on cold caches.
- **Keep sessions scoped and budgeted:** Split large refactors into Hangar DAG leaves instead of 250K+ context marathons, set `budget: {maxTokens, timeoutMillis}` on managed seats in `agent.taskgraph.propose` for unattended/overnight runs, and use `agent.entitlements` to route or pause seats (`GoogleAgent`, `GeminiCli`, `Codex`, `ClaudeSubscription`) when a quota pool is tight.
- **Audit burn before scaling up:** Check `session.score` and `agent.evaluations` to spot high-input/low-output loops early.

## Building on Reaktor

Three skills carry what this framework costs people who learn it by crashing. Load them before touching their domain:
- **`reaktor-app`** — wiring an app: port keys by direction, never reading a port from `init`, adapter install order, routes being per-graph, the two platform-slot patterns.
- **`reaktor-module`** — where a capability belongs: reuse, extend, or a new module, and how to add one to this build.
- **`reaktor-ship`** — getting a KMP app onto both stores: R8 and serialization, permissions that arrive through manifest merge, signing, asset sizes, listing limits.

## Comments

New code has no comments. Names and types carry the meaning; if code needs an explanation, rename or restructure it until it does not. Narrative belongs in the commit message. Existing comments stay until the code around them is rewritten.
