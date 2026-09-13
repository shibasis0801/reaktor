# Conductor: conversation and bounded context

Conductor runs replaceable Codex/Claude CLI harnesses over a Reaktor conversation. The CLI now defaults to one-agent `ask`. Protocols remain explicitly selectable.

## Continue a saved task

From the Reaktor checkout:

```sh
./gradlew :reaktor-conductor:conduct --args='--dry --prompt "Create the first workflow" --thread /tmp/reaktor-thread.json'
./gradlew :reaktor-conductor:conduct --args='--dry --prompt "Continue with persistence" --thread /tmp/reaktor-thread.json'
```

`--dry` uses Echo and makes no model request. Remove it and choose your own `--roster` for actual harness use. A file lock excludes another writer; atomic file replacement checkpoints the prompt, provider identity when started, and completed round results. IDs survive a fresh process. Failure events make the CLI exit nonzero. This standalone protocol command reconstructs bounded canonical context; the workspace service below additionally enables qualified native provider continuation.

Harness success requires a terminal provider success envelope. Cancelling collection reaps the owned subprocess. Requested tool restrictions are not a substitute for host sandboxing, credential scope or action authorization.

Usage summaries report inclusive input, cached input, fresh input (including cache creation), cache creation, output and reasoning output separately. Every metric reports unknown turns; reasoning is not added to output. Legacy records without cache counts cannot produce a complete fresh-input total.

## Shared workspace host and desktop Chat

`AgentWorkspace` exposes a plain JVM API with no graph or GUI dependency. `AgentWorkspaceConnection` supplies one process owner per canonical workspace and the same authenticated MCP boundary for the desktop, CLI and external clients. BestBuds' optional `KernelAgentsNode` projects it into the kernel; the desktop Agent → Chat pane consumes that port.

That describes the current implementation. The [13 September agent-layer plan](experiments/graph-agent-layer-2026-09-13.md) makes the graph the primary owner of tasks, provider sessions, context and operations. The former requirement to build a separate graph-agnostic surface is superseded; existing harnesses and APIs remain useful migration components.

Build the launcher once after source changes, then start the owner:

```sh
reaktor-conductor/tools/agent-workspace --build help
reaktor-conductor/tools/agent-workspace serve --dir /Users/ovd/dev/bestbuds
```

Opening the desktop Chat pane starts or attaches to the same owner. A second `serve` reports the existing owner. Closing the owning desktop/process interrupts its active runs; use a separate headless `serve` process when work should outlive a window. Other connections do not own that process. `serve --dry` exposes only Echo for offline qualification.

The service exposes seven tools: `agent_workspace_info`, `agent_runs`, `agent_submit`, `agent_run`, `agent_wait`, `agent_cancel`, and `agent_transcript`. A submission contains `requestId`, `provider` (`Codex` or `ClaudeCode`), `prompt`, optional `threadId`, optional `model`, `allowWrites`, and optional `ContextPacket` as `context`. Retries of the same request ID and identical input return the saved run; changed input fails. A new request ID starts a new turn.

```json
{"requestId":"feature-001","provider":"Codex","prompt":"Implement the first screen in this workspace","allowWrites":true}
```

Save this JSON and call `agent-workspace submit --dir <workspace> --request <file>`. Use `read`, `wait`, or `cancel` with `--id <runId>`. `wait --after <revision>` waits for a changed revision for up to 30 seconds. Use `transcript --id <threadId>` for canonical history. `runs` lists recent summaries. The desktop provides provider/model selection, Send, Stop, recent conversations, provider session continuation and usage.

Attach external clients over the stdio bridge (no credential copied into configuration):

```sh
codex mcp add reaktor-agents -- /Users/ovd/dev/reaktor/reaktor-conductor/tools/agent-workspace mcp --dir /Users/ovd/dev/bestbuds
claude mcp add --scope local reaktor-agents -- /Users/ovd/dev/reaktor/reaktor-conductor/tools/agent-workspace mcp --dir /Users/ovd/dev/bestbuds
```

Run the Claude registration from the intended project. These are optional installation commands; building this module does not change provider configuration. The bridge requires a running owner and emits JSON-RPC only on stdout. It negotiates MCP 2025-06-18 (used by the qualified Codex CLI) and 2025-11-25. An actual external Codex call to `agent_workspace_info` passed through this bridge. It never starts a short-lived owner that would disappear immediately after a submission.

State lives in `~/.reaktor/agents/<sha256(canonical-workspace)>`, with private directories, atomic private JSON records, a workspace binding, an owner lock and a rotating bearer credential. Credentials never appear in advertised tool results. The recent index holds 200 runs; listing returns at most 50 summaries. Exact older run IDs remain addressable. Output is capped at 12,000 characters; transcript responses retain the last 20 events within a 24,000-character text budget. Two concurrent inspection requests are allowed; a requested editing turn has exclusive agent admission. The HTTP transport bounds workers and queued requests; long waits have separate admission.

Request records, canonical prompts/provider IDs and terminal results survive restart. An unfinished record becomes Interrupted; recovery does not rerun a possible effect. Native continuation is used only immediately after a successful turn by the same unchanged provider specification. Switching providers or recovering an interrupted turn starts a fresh provider session with bounded canonical context.

Codex CLI 0.131.0 emits session-total usage, including on resume. `reportedUsage` retains those raw counters; canonical `usage` holds fresh-session values or a labelled delta from the previous observed counters. Missing/decreasing baselines remain unknown. The delta covers the observed provider-session interval; external activity in that provider session could contribute to it. Session totals are never summed as separate turn costs. See the [qualified upstream implementation](https://github.com/openai/codex/blob/rust-v0.131.0/codex-rs/exec/src/event_processor_with_jsonl_output.rs).

This is local OS-owner access, not delegated client authority. `allowWrites` requests harness policy; Claude's denied editor tools do not sandbox Bash or MCP effects. Existing anonymous Reaktor inspection endpoints remain read-only. This service does not yet unify the kernel operation executor, support interactive approval/input cards, attach arbitrary existing provider tasks, or offer a greenfield generator. The Codex App Server and interactive Claude adapter are the next provider work.

Initial workspace start/resume qualification used Codex CLI 0.131.0 with an explicit `gpt-5.5` model; that CLI rejected the configured `gpt-6-astra`, and Claude 2.1.183 had no usable login. On 13 September, the npm CLIs were upgraded to Codex 0.154.0 and Claude Code 2.1.270, Claude sign-in was refreshed, and actual GPT-6 Astra / Claude Opus 5 inference and the five-turn tabs council succeeded. The [council experiment](experiments/tabs-council-2026-09-13.md) records the design, costs, context limits and parser fixes. This newer qualification covers Conductor's batch council; it does not imply native interactive adapters or automatic MCP registration. Global model defaults were not changed.

## Manna context

The private Manna MCP tool `context_for_task` accepts `taskKey` or `query`, `maxChars` (2,000–24,000), `maxItems` (1–40) and dependency `depth` (0–4). It applies existing Manna workspace visibility before retrieval. Save its `structuredContent.data` object as JSON and pass `--context /path/context.json` to Conductor. The output is the version 1 `ContextPacket` contract.

History, aggregate peer material and retrieved context each have a 24,000-character default cap. These are character limits, not token measurements or a cap on the user's task/instructions. Oversized packets are explicitly omitted as a whole. The current prompt is not repeated in history. Upstream revision and freshness stay unknown where the source cannot prove them; retrieval time is not a source revision. This packet is evidence, not permission or trusted instructions.

## Local PostgreSQL, pgvector, Memgraph and Manna data

The optional single-operator bootstrap is in `tools/local-context`. It creates persistent Docker volumes, binds only localhost, stores a random PostgreSQL password and authorized exports outside the repository, and caches an ONNX embedding model locally.

```sh
reaktor-conductor/tools/local-context/local-context setup
reaktor-conductor/tools/local-context/local-context import /path/authorized-manna-export.json --embed
reaktor-conductor/tools/local-context/local-context query 'Reaktor authentication persistence' \
  --tenant manna --workspace shibasis --principal local-owner:manna:shibasis --semantic > /tmp/context.json
```

The example scope describes the local owner's exported Manna workspace. It is not a remote identity assertion or a multi-user authorization mechanism. Only import data exported under the intended identity. Future shared/client hosts must resolve scope and current ACLs on the server; never expose this bootstrap to clients directly.

- PostgreSQL + pgvector: `127.0.0.1:55439`, database/user `reaktor_context`.
- Memgraph Bolt: `127.0.0.1:57689` (matches the currently deployed engine version).
- Default private state/model/export directory: `~/Library/Application Support/Reaktor/local-context`; override with `REAKTOR_LOCAL_DATA_DIR`.
- `status`, `stop` and `up` manage the two local services. `stop` preserves volumes. No automatic volume deletion command is provided.
- `import` activates a content-addressed local snapshot, upserts changed/missing embeddings, and builds its relationship projection in Memgraph. It does not write upstream. Data refresh currently requires another authorized export/import; background sync and revocation propagation remain planned.
- `query` uses PostgreSQL full-text search and Memgraph links. `--semantic` additionally performs local CPU query embedding and scoped exact pgvector search, combining ranks. Query embedding uses cached model files only. Cold model startup is included in reported latency.
- Local snapshots remain partial and freshness unknown. The snapshot digest identifies local content; it does not prove an upstream revision. Mutations must revalidate against the live application.

The initial local subscription contains all 55 tasks and 11 goals visible to the connected Shibasis workspace, their 103 linked learning resources and 545 relationships. The full catalog has 255 resources; unrelated resources are not mirrored in this first subscription. The private export is not checked into Git.

`PgVectorCandidateSearch` is the reusable JVM adapter. Its caller provides a trusted authorization/current-source-revision allowlist and a DataSource; it returns candidate references only. SQL scopes by tenant, workspace, embedding model, dimension and exact source revision before cosine scoring. `sql/context-pgvector.sql` is optional host-owned provisioning, not a production migration. The bootstrap demonstrates ingestion/search locally; it is not yet installed as the shared desktop workspace service.

## Verification

```sh
./gradlew :reaktor-conductor:jvmTest --offline --no-daemon --console=plain
```

The live pgvector test is opt-in via `REAKTOR_CONTEXT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:PORT/reaktor_context_test`, against a **disposable** database with user `postgres` and password `conductor-test-only`. The fixture truncates its test table. Normal runs skip this test. No paid model calls are needed.
