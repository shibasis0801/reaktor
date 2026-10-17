# Reaktor agent workflows — delivery, 14 September 2026

The remaining implementation extends the graph task workspace with authored workflows, durable gates/forks, isolated source changes, native agent controls, local retrieval/memory and service operations. Codex and Claude remain native harnesses; Reaktor owns the graph, causal evidence and execution lifecycle.

## Delivered

- **Workflow authoring:** stages, participants, instructions, text/Decision contracts, conditional dependencies, add/remove/connect controls, JSON editing and revisioned reusable runbooks. A compact stage rail replaces a long list of open forms. The existing Pencil 08.03 board guided the layout; no Pencil edit is claimed.
- **Durable execution:** bounded acyclic workflows; sequential ready-stage execution; explicit joins; immutable checkpoint history; fresh forks or invalidation of a stage and descendants. Reused evidence requires unchanged source. Gate approval requires the exact reviewed run revision. A failed independent branch cannot be hidden by success elsewhere.
- **Real checks and repair:** named kernel checks, exact source/log receipts, idempotent admissions, one bounded repair attempt and explicit final review. A failed check routes to repair before review. Cancellation stops the owned kernel process. Check results and gate decisions are canonical conversation events with causal parents.
- **Native orchestration:** participant steering/interruption, recorded parent/child topology and outputs, and exact-turn Codex child controls where available. Child lifecycle events cannot complete the parent or enter its answer. Claude child activity remains observable without claiming independent control its transport does not expose.
- **Isolation:** owned detached worktrees include the complete dirty baseline and sibling included repositories, preserving the real Git index. Review shows task-only diffs and conflicts; apply requires the exact patch and unchanged source. Partial multi-root outcomes are retained for reconciliation.
- **Context:** bounded traversal of the mounted Reaktor graph with typed ports, actual edges, distinct runtime/definition/source identities, and a serialized wire budget. Local Manna text/semantic search uses PostgreSQL/pgvector, Memgraph and cached ONNX embeddings. Source-linked memory retains exact native assertions with run/event/subject provenance; changed or expired source is excluded by default.
- **Operations:** drain and service replacement, runtime image retention, verified activity archives preserving cursors, Linux/Windows supervisor adapters, and SSH connections to an independently provisioned host. Recent workflow evaluation receipts expose definition fingerprints, outcomes, attempts and reported usage.
- **External integration:** project-scoped Codex/Claude workspace and graph bridges, registered locally for both BestBuds and Reaktor. Machine-specific registrations are excluded locally from Git. The graph bridge discovers the current background kernel port and verifies workspace identity on every request, including after replacement.

## Live qualification

The installed BestBuds launchd owner ran **Codex investigator → Claude reviewer → operator gate** against the current Workflow.kt source. Both providers performed actual native file reads. Claude requested permission to read the sibling repository; the exact authorized read was approved through Reaktor. Claude returned a valid pass Decision. The gate paused durably and completed on reviewed resume with no extra model call. The transcript has both provider identities and causal links, plus the gate event.

Run: `ff781d2fc86f8dee0a6579543460719c9719a70810a73fd143c5e5ab8b29cf4b`  
Task: `809e6c12-3dc3-423f-970a-6fd5df53bd53`

Reported usage: **119,474 inclusive input tokens**, of which **80,294 were cache reads**; **786 output tokens**. The two Reaktor-compiled prompts were 5,613 and 6,522 characters. Native instructions, tool schemas and other harness context are outside those character counts. These observations do not establish token savings or a combined billing total. Gate resume preserved the two-turn usage without charging reused results as new turns.

Local authorized export reindexing reused its cached embeddings: **169 documents, 545 relationships, zero new embeddings, 610 ms reported import time**. Through the workspace MCP endpoint, text search returned 10 entries in **812 ms**, and semantic search returned 10 in **1,054 ms**. These are single local observations including process startup, not load-test percentiles. Both databases remain local and healthy. Source freshness stays **unknown**, reflecting the original export rather than the time of reindexing.

The packaged kernel service was upgraded with the new drain path. A subsequent cold start exposed the old 15-second readiness limit; readiness now uses a bounded 60-second wall-clock deadline. The owner process changed, while the completed native workflow, runbook library and kernel check catalog remained available. A separate launchd test exercised crash/restart and reviewed recovery. The earlier delivery already verified work continuing after the desktop process exited.

## Validation and artifacts

`/Users/ovd/dev/reaktor-context/agent-workflows-2026-09-14/evidence/verification.json` records exact final suite counts (141 framework, 123 kernel, 32 agent UI/design tests; zero failures, 18 opt-in skips); JUnit XML and build logs are retained beside it. Coverage includes malformed decisions, failed branches, stale gate/fork rejection, canonical check dependencies, idempotent check admissions, cancellation of check processes, dirty-baseline preservation, exact patch apply, memory invalidation, native child isolation, graph-port rediscovery, archive cursors, supervisor configuration and actual kernel service replacement. Agent interaction/render, headless-boundary and design-token/truth checks passed. UI fixture screenshots cover desktop and narrow widths.

The Mac was locked during the final native UI check, so this delivery does **not** claim an unlocked live click-through of the new preview. Compose interaction tests and rendered screenshots were inspected; the actual packaged background service and live providers were exercised separately.

Preview: `/Users/ovd/dev/reaktor-context/agent-workflows-2026-09-14/release/main/app/ReaktorAgentWorkflowPreview.app`. Open **Agent → Workflow** and choose the installed **Build → review → repair** runbook, attach the graph selection in Context, choose source isolation/checks, then start. Native terminal sessions may need restarting or MCP reload to see newly registered project servers.

## Boundaries still requiring further work or configuration

- Workflow editing uses stages and branch controls; it is not a draggable freeform canvas. Ready stages execute sequentially; Compare/Council supply parallel native rounds. Arbitrary cyclic execution and inference-level time travel are not implemented.
- Kernel check stages currently run only against Shared source. Worktree rebase, atomic cross-repository apply and worktree/checkpoint garbage collection remain manual. Unknown arbitrary effects are never promised exactly once or automatically compensated.
- Native child visibility/control depends on provider events. Codex child targeting has protocol fixtures; exhaustive live child/provider-version conformance remains unqualified. Claude has no independent child steering API in this transport.
- Automatic upstream Manna ACL/freshness sync and delegated client authorization are not installed. Changed authorized local exports are automatically reindexed; a stale export cannot establish current access. This remains a private local-owner workspace.
- SSH transport is implemented, but no always-on destination was supplied. Linux and Windows have configuration tests, not live OS qualification. Local work cannot run while its host is powered off.
- Evaluations are observed run receipts, not controlled regression benchmarks. Native context overhead remains partly unattributed. Retention is an explicit maintenance operation; no automatic deletion schedule or binary rollback is claimed.

Most implementation files were captured by concurrent `progress` commits (`9ab009f3` in Reaktor, `63360964` in BestBuds). Final follow-up commits retain the remaining correctness fixes, bridge discovery and verification. Concurrent telemetry/Grafana changes are outside this delivery.
