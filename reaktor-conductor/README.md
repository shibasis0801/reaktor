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

`AgentWorkspace` is the headless execution owner for graph task protocols and canonical conversation evidence; it has no Compose dependency. `AgentWorkspaceConnection` supplies one process owner per canonical workspace and the same authenticated MCP boundary for the desktop, CLI and external clients. BestBuds' optional `KernelAgentsNode` projects it into the kernel; the desktop Agent pane consumes that port.

That describes the current implementation. The [13 September agent-layer plan](experiments/graph-agent-layer-2026-09-13.md) makes the graph the primary owner of tasks, provider sessions, context and operations. The former requirement to build a separate graph-agnostic surface is superseded; existing harnesses and APIs remain useful migration components.

Build the launcher once after source changes, then start the owner:

```sh
reaktor-conductor/tools/agent-workspace --build help
reaktor-conductor/tools/agent-workspace start --dir /Users/ovd/dev/bestbuds
```

Opening the desktop Chat pane starts or attaches to the same owner. A second `serve` reports the existing owner. The desktop now connects to a macOS launchd service with its own headless kernel; closing the pane or desktop only detaches the UI. `workspace start` and the MCP bridge also start or attach to a supervised owner. Explicit `serve` remains a foreground owner whose exit ends its processes. Existing foreground owners are attached to without interrupting their work; stop them before switching to background supervision. `serve --dry` exposes only Echo for offline qualification.

The core conversation tools are: `agent_workspace_info`, `agent_runs`, `agent_submit`, `agent_run`, `agent_wait`, `agent_cancel`, and `agent_transcript`. A submission contains `requestId`, `provider` (`Codex` or `ClaudeCode`), `prompt`, optional `threadId`, optional `model`, `allowWrites`, and optional `ContextPacket` as `context`. Retries of the same request ID and identical input return the saved run; changed input fails. A new request ID starts a new turn.

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

State lives in `~/.reaktor/agents/<sha256(canonical-workspace)>`, with private directories, atomic private JSON records, a workspace binding, an owner lock and a rotating bearer credential. Credentials never appear in advertised tool results. The recent index holds 200 runs; listing returns at most 50 summaries. Exact older run IDs remain addressable. Output is capped at 12,000 characters; transcript responses retain the last 20 events within a 24,000-character text budget. Two concurrent runs are allowed. Shared-source editing is exclusive; editing in owned worktrees can overlap. The HTTP transport bounds workers and queued requests; long waits have separate admission.

Request records, canonical prompts/provider IDs and terminal results survive restart. An unfinished record becomes Interrupted; recovery does not rerun a possible effect. Native continuation is used only immediately after a successful turn by the same unchanged provider specification. Switching providers or recovering an interrupted turn starts a fresh provider session with bounded canonical context.

Codex CLI 0.131.0 emits session-total usage, including on resume. `reportedUsage` retains those raw counters; canonical `usage` holds fresh-session values or a labelled delta from the previous observed counters. Missing/decreasing baselines remain unknown. The delta covers the observed provider-session interval; external activity in that provider session could contribute to it. Session totals are never summed as separate turn costs. See the [qualified upstream implementation](https://github.com/openai/codex/blob/rust-v0.131.0/codex-rs/exec/src/event_processor_with_jsonl_output.rs).

This is local OS-owner access, not delegated client authority. `allowWrites` requests harness policy; Claude's denied editor tools do not sandbox Bash or MCP effects. Existing anonymous Reaktor inspection endpoints remain read-only. The desktop uses interactive native Single turns by default, with explicit batch fallback and batch Compare/Council. It exposes live questions, permissions, steering, queued follow-ups, Changes and delivered Context. Kernel execution still uses its existing operation planning path; attaching arbitrary existing provider tasks and a complete greenfield generator remain pending.

Initial workspace start/resume qualification used Codex CLI 0.131.0 with an explicit `gpt-5.5` model; that CLI rejected the configured `gpt-6-astra`, and Claude 2.1.183 had no usable login. On 13 September, the npm CLIs were upgraded to Codex 0.154.0 and Claude Code 2.1.270, Claude sign-in was refreshed, and actual GPT-6 Astra / Claude Opus 5 inference and the five-turn tabs council succeeded. The [council experiment](experiments/tabs-council-2026-09-13.md) records the design, costs, context limits and parser fixes. This newer qualification covers Conductor's batch council; it does not imply native interactive adapters or automatic MCP registration. Global model defaults were not changed.

## Native control and candidate evidence (13 September 2026)

`AgentSubmission.transport` accepts `Automatic`, `Interactive` and `Batch`. Automatic selects interactive Single turns and batch collaboration. Native sessions remain alive while a turn runs; completed turns close their process and use the provider's saved session for compatible continuation. Reaktor does not claim to keep one process alive across completed turns.

The owner injects its workspace-bound MCP configuration into both harnesses while retaining other native configuration. The installed Codex 0.154.0 and Claude Code 2.1.270 both called `agent_workspace_info` through their native tools and returned the correct temporary workspace. Current Codex command/file/input/permission/elicitation codecs have fixtures. Live tests additionally exercised Codex command denial and Claude question answering followed by permission denial, including stale duplicate responses. Elicitation, Codex questions, native subagents and exhaustive provider-version conformance still need live qualification. A successfully written approval response means sent to the provider; it is not proof that the action executed.

Use `workspace install --dir <project>` to register project-scoped `.codex/config.toml` and `.mcp.json` entries. A private ownership manifest under `.reaktor/agent-bundle.json` supports repeat installation, updating owned entries, and safe uninstall. Existing or modified entries are preserved. Global legacy entries are not automatically removed. The graph bridge reads the current private `graph-connection.json` on each request and checks `/workspace/identity` before forwarding. An explicit `--graph-url` overrides discovery; port 8765 is a legacy fallback when no background kernel is published. It needs the updated kernel for that workspace. The agent workspace remains usable when that separate kernel is absent.

Task evidence is stored locally under the workspace's private `evidence` directory:

- `agent_task_graph` projects task → candidate → subject, finding → candidate and check → candidate relationships from the saved records.
- `agent_candidate_capture` fingerprints working files, deletions, executable modes, symlinks, non-ignored untracked inputs, nested repositories and literal Gradle included-build roots. HEAD, graph definition identity and source content identity remain separate. Dynamic build roots, missing repositories and capture budgets produce explicit incomplete coverage. This is a working-source observation, not a transactional filesystem snapshot or a reproducible environment image.
- `agent_artifact` retrieves bounded UTF-8 byte ranges of attached local diff/check artifacts. Tracked changes and bounded untracked-file diffs are included; binary and over-budget content is qualified explicitly.
- `agent_finding` records a producer-attributed finding against validated candidate references. `agent_finding_resolve` records repair evidence. These records are claims, not automatically proven diagnoses.
- `agent_collect_check` imports a real receipt and verifies the retained artifact digest from the workspace-matched local kernel. Kernel Develop/Testing operations retain non-sensitive logs up to 16 MB, preserve early diagnostics beyond the UI event tail, and compare source identity before and after execution. Sensitive output remains withheld. Legacy event tails are explicitly partial and have no fabricated full-log reference.
- `agent_acceptance_checks` declares required kernel task IDs. `agent_candidate_accept` requires a current complete candidate, passing imported checks and resolution of blocking findings on that candidate. Acceptance does not commit, merge or deploy.
- `agent_queue`, `agent_queue_items` and `agent_queue_cancel` retain exact follow-ups. They run sequentially after successful predecessors; failure or explicit cancellation blocks dependent work. A supervised owner retains waiting follow-ups across restart and dispatches them only after their predecessor completes or safely recovers.

Workbench selection now includes a bounded two-hop neighborhood of the mounted Reaktor graph: node definitions, runtime identities, typed ports and actual edges. Unknown source revisions and unloaded scopes remain explicit. The Context pane previews/excludes attachments and retrieves the installed local Manna scope or source-linked workspace memory. Neither retrieval system changes graph authority.

## Background work, recovery and activity

The desktop agent node connects to a workspace-scoped macOS LaunchAgent. The service hosts the kernel graph, native harnesses, local checks and the authenticated agent MCP endpoint. Its classpath is retained in private content-addressed JARs so `gradle clean` cannot remove the code required for restart. The desktop bundle retains `bin/java` for this process and its MCP bridges. The application/JDK installation must remain available; this is not a machine-independent execution image.

| Event | Behavior |
| --- | --- |
| Close the agent pane or quit the desktop | Work and local checks continue in the service; reconnect attaches to the same task. |
| UI connection drops | The task view retries observation with backoff; it does not resubmit work. |
| Service exits or crashes | launchd restarts it. Completed protocol stages are reused with stable event identities. Pending admissions and queued turns remain local. |
| A native stage/tool has no durable outcome | Recovery enters **Needs review**. Inspect native activity and source changes, then resume the unfinished stage or stop the task. Completed stages are not called again. |
| A provider was awaiting an answer | Old process control handles are cleared. Recovery requires review; approvals are never replayed. |
| Explicit **Stop task** / `workspace cancel` | Cancellation is saved before stopping execution; the task is not automatically resumed. |
| `workspace stop` | Unloads the service for this login; checkpoints remain. It can restart on the next explicit start or macOS login. |
| Sleep, logout, reboot or power off | No local execution while the machine/session is unavailable. The user LaunchAgent starts at login and inspects saved work. |

`workspace resume --id <runId>` / `agent_resume` means the caller reviewed an interrupted stage and authorizes retrying its remaining work. It is not exactly-once execution of arbitrary tool effects, a rollback, or a resume from the middle of model inference. After three automatic recovery attempts, further recovery requires review. Workflow gates additionally require `--expected-revision <reviewed-run-revision>` (MCP `expectedRevision`). Linux systemd-user and Windows logon-task adapters are implemented with configuration tests; macOS remains the live-qualified platform. An optional SSH profile connects to an independently provisioned always-on host.

`workspace activity --id <runId> --after <cursor>` / `agent_activity` pages a durable journal of native tool lifecycle, available plan/agent/compaction events, controls and recovery. Entries retain provider identity, attempt, action parent, subject references, bounded input/output and truncation. They are recorded provider events, not access to hidden reasoning. The UI offers grouped/detailed views, filters and durations. Partial reply text is checkpointed at most once per second while streaming; the latest unsaved fragment can be lost in a crash. Completed stage output is separately durable. Explicit maintenance tools compress old terminal activity without changing cursors and prune expired unreferenced runtime images. They preserve task evidence and worktrees; no automatic deletion schedule is installed.

The desktop also persists task drafts locally (300 ms debounce, flush on close), previews next-turn context with per-entry exclusions, and resolves recorded node selections back into the matching graph/context. Mounted graph dependency coverage is bounded and explicitly partial. The service's `agent_check_catalog`, `agent_check_start` and `agent_check_link` run admitted local Develop/Testing checks through the kernel, retain logs, and link terminal evidence to the exact checked source candidate. Source changes during a check invalidate its revision claim. Deployments and other higher-effect operations are excluded from this shortcut. The standalone framework service has no product check catalog; open the desktop first to install the kernel-hosted service.

Qualification includes actual launchd client detachment, SIGKILL/restart and reviewed recovery, automatic reuse after a completed-stage checkpoint, queue and explicit-stop semantics, kernel checks through MCP, and live tool lifecycle capture from installed Codex 0.154.0 and Claude Code 2.1.270. The workflow, checkpoint, isolation and context extensions below build on this foundation. Delegated client authority and arbitrary effect compensation remain outside the local-owner execution contract.

## Manna context

The private Manna MCP tool `context_for_task` accepts `taskKey` or `query`, `maxChars` (2,000–24,000), `maxItems` (1–40) and dependency `depth` (0–4). It applies existing Manna workspace visibility before retrieval. Save its `structuredContent.data` object as JSON and pass `--context /path/context.json` to Conductor. The output is the version 1 `ContextPacket` contract.

History, aggregate peer material and retrieved context each have a 24,000-character default cap. These are character limits, not token measurements or a cap on the user's task/instructions. Oversized packets are explicitly omitted as a whole. The current prompt is not repeated in history. Upstream revision and freshness stay unknown where the source cannot prove them; retrieval time is not a source revision. This packet is evidence, not permission or trusted instructions.

## Shared comparison and council

The desktop Team view and `agent_submit` now expose the existing conductor protocols through one workspace owner:

```json
{
  "requestId": "tabs-council-unique-request",
  "provider": "Codex",
  "prompt": "Compare graph-owned tab lifecycle designs and retain unresolved disagreements.",
  "collaboration": "Council",
  "partner": { "provider": "ClaudeCode" },
  "allowWrites": false
}
```

`Single` is the backwards-compatible default. `Compare` produces two independent proposals; `Council` produces two proposals, two critiques and a synthesis by the primary provider. Both require a different configured partner and reserve two concurrency slots. Optional `model` and `partner.model` remain independent. Collaborative turns use fresh provider sessions and preserve native operator configuration. Editing requires `isolation: "Worktree"`; provider tools still govern possible effects.

Run receipts retain bounded participant streams, their latest native session references, all completed-turn usage and any participant failure. A successful synthesis cannot make a failed council successful. `turnUsage` reports known values and unknown-turn counts; it is not a billing total. Historical stage outcomes live in the canonical transcript. Ephemeral council sessions are never resumed as later single turns.

The desktop has Conversation, Workflow, Agents, Changes, Context and Activity views plus recent-task search. The complete researched destination, six Pencil boards and delivery gates are in [Agent Workspace](https://reaktor.build/docs/reaktor-agent-workspace). Interactive provider input/approval/steering and current Workbench selection context are wired. Owned worktrees and editable durable workflows are available; graph context covers the mounted neighborhood rather than every unloaded scope. Current council stages may continue after a participant failure, which remains visible in the terminal receipt.

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

`PgVectorCandidateSearch` is the reusable JVM adapter. Its caller provides a trusted authorization/current-source-revision allowlist and a DataSource; it returns candidate references only. SQL scopes by tenant, workspace, embedding model, dimension and exact source revision before cosine scoring. `sql/context-pgvector.sql` is optional host-owned provisioning, not a production migration. The workspace service adapts this existing local store through an operator-installed subscription; it does not add a second model or orchestration service.


## Graph workflows, isolation and operations (14 September 2026)

`AgentSubmission.workflow` is a versioned `WorkflowDefinition` executed by `Protocol.Graph` over the canonical `ThreadDocument`. It names participants, stages, graph subject references and conditional edges. The Workflow pane edits stages, roles, instructions, result contracts and branches; an advanced JSON editor also covers the full schema. Saved runbooks use optimistic revision checks.

- Agent stages return text or strict JSON `{ "verdict": "pass|repair|fail", "summary": "..." }`. Invalid decisions fail closed. Native output is never treated as executable control metadata.
- Check stages execute a named, locally admitted kernel check. Check identity is `runId:stageId`; retries return the original admission/receipt. Unknown admission outcomes require inspection. Cancel stops the owned check process as well as native work.
- Gate stages wait durably. Continuing requires the reviewed run revision and unchanged source. Checks and gate decisions become canonical events with causal parents and kernel receipt references. Acceptance, worktree apply, commit and deployment remain separate operations.
- A join waits for all predecessors and runs when any incoming branch matches. Workflows are bounded acyclic graphs (64 stages maximum) and execute ready stages sequentially. The build → check → review → repair template expands one bounded repair attempt; a failed check cannot be replaced by a model saying “pass.” Compare/Council retain their native parallel rounds.
- `agent_checkpoints` lists immutable checkpoints (latest 200). `agent_checkpoint_fork` creates a new task and fresh native sessions. Reusing unaffected results requires unchanged source; omitting `fromStage` recomputes everything. This is stage-level replay, not native inference time travel.

`isolation: "Worktree"` snapshots the complete dirty baseline with a private Git index, including non-ignored new files. Each parallel participant receives an owned detached checkout. Sibling included Git repositories are retained; other source-root arrangements fail explicitly. `agent_worktree_review` shows task-only changes and conflicts. Apply requires matching source/patch digests and an idle workspace, preserves the real Git index and records uncertain partial multi-root outcomes without replaying them. Kernel check stages currently require Shared isolation. Source changes require manual reconciliation; automatic rebase and worktree garbage collection are not implemented.

The Agents view shows native child records, parent links and available results. Codex controls require an observed child and its exact active turn. Child events cannot finish or contaminate the parent. Claude child activity is visible, but its current transport exposes no independent live child control. These are observed capabilities, not a promise that every provider-version child event is available.

`agent_memory_remember` retains an exact completed stage assertion with its source/run/event/graph provenance. Search excludes changed-source or 30-day-old assertions by default. Historical results must be explicitly requested and remain labelled stale; they never satisfy acceptance checks. The Context pane can attach individual results. Reaktor prompt character counts and reported native token/cache counts remain distinct; unreported native overhead is not estimated.

Install a private `local-context.json` in the workspace agent directory with `workspaceRoot`, `tenantId`, `workspaceId`, `principalId`, absolute `launcher`, `exportPath` and `dataDirectory`. `agent_context_status/search/refresh` use the existing local PostgreSQL/pgvector/Memgraph and cached ONNX model. A changed authorized export triggers reimport, including removals, before search. Upstream Manna ACL/freshness synchronization is not configured: an old local export never becomes current merely because it was reindexed.

`agent_evaluations` reports recent workflow definition fingerprints, source candidates, outcomes, attempts and reported usage. It is observational evidence, not a controlled benchmark or a token-savings claim.

`agent_service_drain` closes admission while current work finishes; `workspace upgrade --request <Java-command-array.json>` snapshots the replacement image, waits for idle, stops the supervisor and waits for the old JVM to exit before starting its replacement. The kernel main also accepts `--install <root>` and `--upgrade <root>` using its current packaged classpath, preserving graph/check hosting. A failed startup retains task data but does not promise automatic binary rollback.

`agent_activity_compact` archives old terminal activity with byte verification and original cursors. `agent_runtime_prune` retains the running JVM, installed supervisor and recent image manifests, with a minimum seven-day retention. Snapshot and prune operations share a cross-process lock. Neither removes source worktrees, task records, checkpoint history or evidence.

For an always-on host, create a private `remote.json` with `{ "host": "ssh-config-alias", "workspaceRoot": "/absolute/project", "launcher": "/absolute/agent-workspace" }`, or pass `--remote <file>` to the CLI. The remote machine must already have its workspace, appropriate kernel-hosted supervisor and its own Codex/Claude login. Reaktor uses SSH BatchMode and literal arguments; it never copies local provider credentials. Desktop selections containing local runtime handles are withheld from remote tasks. Disconnecting an observer leaves remote work owned by its host. No remote host is provisioned automatically.

Supervisor behavior follows [systemd's service contract](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml) and [Windows Scheduled Tasks settings](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtasksettingsset?view=windowsserver2025-ps). Native Codex controls follow the [App Server protocol](https://learn.chatgpt.com/docs/app-server). Linux/Windows adapters and SSH argument handling have local configuration tests; live host qualification requires those operating systems and a configured destination.

## Verification

```sh
./gradlew :reaktor-conductor:jvmTest --offline --no-daemon --console=plain
```

The live pgvector test is opt-in via `REAKTOR_CONTEXT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:PORT/reaktor_context_test`, against a **disposable** database with user `postgres` and password `conductor-test-only`. The fixture truncates its test table. Normal runs skip this test. No paid model calls are needed.
