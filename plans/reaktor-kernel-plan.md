# One Reaktor kernel for the CLI, MCP and Hangar

Written 14 October 2026. Not published. This file holds the broader kernel design; `kernel-handoff.md` owns work-package sequencing and qualification. The 21 October refinement of [cli-kernel-hld.md](cli-kernel-hld.md) is the canonical CLI/TUI experience contract and supersedes older CLI grammar/output examples here. It adds an explicit TUI milestone, K1-TUI; it does not qualify implementation. The status rows below retain the earlier implementation snapshot; use the handoff and Dependeasy domain status for current evidence.

## The idea

Each workspace has one kernel process. It holds one graph, one operation catalog, one store and one wire. The CLI, MCP and Hangar are faces: each reads the same catalog and calls the same `invoke`, so they share runs, plans, approvals and receipts.

The vocabulary is open source in `reaktor-tooling` (`dev.shibasis.reaktor.tooling.operation`), so every Reaktor+ product (BestBuds, Manna, Nexergy, CompanyOS) contributes its own operations. The kernel host, the BestBuds catalog and the CLI stay closed source in bestbuds.

Before this work (scan of 14 October): 4 kinds of kernel process, 6 kinds of endpoint, about 12 dispatch paths, 13 operation descriptor shapes, 5 catalogs, 6 fingerprint functions, and every surface with its own state file.

## Status at a glance

| # | Domain | State |
|---|---|---|
| 1 | One operation model | **Done.** `Operation<I,O>`, `OperationDescriptor`, `Invocation`, `Outcome`, schema from serializer, `OperationNode`, `OperationKernel` with door policy, single-use approvals, call log, request-id replay, legacy `ToolNode` adapter, built-in `operation.catalog`/`operation.describe` |
| 2 | One kernel process per workspace | Not started. Today the agent owner (`owner.lock`) hosts it; Hangar's embedded control plane is still separate |
| 3 | One catalog | **Started.** Typed `build.*` and `run.*`; 22 engineering operations (`code.*`, `context.query`, `task.*`, `check.*`, `peer.exchange`, `provider.*` reads and refresh) bridged with explicit effects; kernel reads join through the adapter; superseded `build_*` reads removed from the graph. Still to move: `code.apply` and `provider.call` (own approval paths), cloud, devices, database, telemetry writes, desktop reads |
| 4 | The CLI | **In progress.** Primary `reaktor <domain> <verb>` face is installed: typed schema arguments, human/JSON output, shell completion and durable following qualify; old parsers/archive are removed. Scoped BuildOption schema/default/source metadata and actual CLI property/task receipts qualify (dfcf03ee/29233fe4); public output options, affected execution, approval/forms/caller migration and K1 acceptance remain |
| 5 | Dependeasy domain | **Started.** `build.list/show/tree/plan/run/receipts` read the model and use the control plane. `deploy.*` waits for WP16/WP21 kernels |
| 6 | MCP | **Started.** The owner serves a `kernel` tool; the stdio bridge (`reaktor-agent workspace mcp --seat <harness>`) serves `kernel` beside `workspace` and stamps the harness's seat on every call |
| 7 | Hangar | Kernel approvals and calls already appear in its door pane (same directories). Panes still use the embedded control plane |
| 8 | Plans, approvals, receipts | Approvals unified for kernel operations through the door store. Control-plane pending-plan slot and five other fingerprints remain |
| 9 | Reaktor+ and framework hooks | Not started |
| 10 | Identity and authority | Not started. Seat is caller-supplied attribution, as in the door |
| 11 | Bugs | `provider.refresh` key fixed; `workspace serve --connector/--tunnel-origin` accepted; `workspace mcp --seat` now reaches the kernel. `reaktor mcp --stdio` still drops it (in `Main.kt`) |
| 12 | Docs and rollout | K0 and WP13/14/16/17 are qualified/pushed; current K1/K3 slices are installed and checked. Public CLI/build guides reflect the primary face. Remaining 23 implementation checkpoints, including required K1-TUI, are tracked in the handoff and full domain status |

## Domains, highest leverage first

1. **One operation model** (`reaktor-tooling`, open)
   - An operation is a typed value: dotted id, summary, one `SafetyClass` effect, input and output serializers, and `suspend OperationCall.(I) -> O`. A long-running operation returns a run reference; `run.*` operations follow it. That replaces a mode flag.
   - Input and output schemas come from the serializer (`SerialDescriptor.jsonSchema()`), with `@Describe` and `@Subject` read from the descriptor. Hand-written `JsonObject` schemas go away.
   - Every invocation goes through one gate: `DoorAuthority.decide` with the seat policy, then the single-use `DoorApprovals` store, then the `CallLog`. Kernel operations are no longer self-governed.
   - Still to fold in: `McpTool`, `McpReadTool`, `TaskEffect`, `ApiEffect`, `WorkerEffect`, `CloudOperation`, the engineering string lists, and an explicit rank on `SafetyClass` (its declaration order and `JvmProjectDiscovery.riskRank` disagree).
2. **One kernel process per workspace** (bestbuds `modules/kernel`)
   - One owner: the background service by default, Hangar when it starts first. One store that only the owner writes.
   - One discovery file replaces `connection.json`, `kernel-connection.json`, `graph-connection.json` and the 8765–8774 scans.
   - One versioned request, `{operation, arguments, caller, requestId}`, with streamed run events.
3. **One catalog**
   - Every capability becomes an operation on an `OperationNode` attached to the kernel graph. Product catalogs are graph nodes.
   - Delete each parallel describer and dispatch path when its consumers have moved.
4. **The CLI** (bestbuds `targets/reaktorCli`, closed)
   - `reaktor <domain> <verb> [subject] --typed-options`, generated from the catalog. Help and completion from the cached catalog (`~/.reaktor/agents/<digest>/kernel-catalog.json`).
   - `--json` gives one JSON value on stdout; `--args -` reads JSON from stdin; results that name a run are followed until it ends, unless `--detach`.
   - A terminal with a person at it can answer an approval in place. Anything else gets exit code 3 and the approval id.
   - Exit codes: 0 done, 1 failed, 2 refused or denied, 3 waiting for a person.
5. **Dependeasy as a domain**
   - Today: `build.list/show/tree/plan/run/receipts`. Next: `build.explain`, `build.kernels`, and the tree fields once WP16 adds them.
   - `deploy.plan/apply/verify/rollback` call dependeasy's `DeployKernel` directly when WP16 and WP21 land.
   - Gradle and deploy receipts join the one run ledger.
   - Codex keeps the model and the kernels (WP12–WP26). This plan takes over the CLI, MCP and Hangar ends of WP18, WP21 and WP26.
6. **MCP**
   - The stdio bridge serves the `kernel` tool with the harness's seat.
   - Progressive disclosure through `operation.catalog` and `operation.describe`. Effect hints come from the real effect. The protocol era is handled at the edge.
7. **Hangar**
   - Panes call `invoke` and subscribe to the owner's snapshot and event stream. Approval UI covers every operation, including second approval.
8. **Plans, approvals, receipts**
   - Plans kept by id, replacing the global pending slot. One fingerprint function. One approval store. One ledger over kernel runs, agent runs, door calls, Gradle receipts and deploy receipts.
9. **Reaktor+ and the framework**
   - One CLI that addresses any portfolio workspace.
   - Hooks: graph interceptors, `reaktor-telemetry` spans per invocation, `reaktor-code`, `reaktor-conductor`, `reaktor-cloud` and the cloud APIs, devtools devices, `reaktor-auth-core` principals, `reaktor-notification` for approvals on a phone, and Blueprint for the build tree.
10. **Identity and authority**
    - Each connection carries a principal. The agent channel never approves. Effects are labelled truthfully: `telemetry_refresh` and `build_plan refresh=true` start runs but are registered as reads.
11. **Bugs and cleanup**
    - Two door instances in one kernel process (`KernelDoor`, `KernelCrashes`). Two default state directories. Port 8765 contention.
12. **Docs and rollout**
    - Keep this file current. Keep package status in `kernel-handoff.md`, CLI/TUI experience in `cli-kernel-hld.md`, and shipped commands in the CLI README; reconcile references at each qualified cutover.

## Defaults taken (owner may change)

- The background service owns the kernel; Hangar is a client of it.
- The vocabulary is open source in `reaktor-tooling`; the CLI and kernel host are closed source.
- A small in-house schema generator instead of the experimental `kotlinx-schema`.

## Code

| What | Where |
|---|---|
| Vocabulary and schema | `reaktor-tooling/src/commonMain/kotlin/dev/shibasis/reaktor/tooling/operation/{Operation,OperationSchema}.kt` |
| Kernel gate | `reaktor-tooling/src/jvmMain/kotlin/dev/shibasis/reaktor/tooling/operation/OperationKernel.kt` |
| Tests | `reaktor-tooling/src/jvmTest/.../operation/OperationKernelTest.kt` (6) |
| BestBuds operations and owner tool | `bestbuds/modules/kernel/src/main/kotlin/ai/bestbuds/reaktor/kernel/KernelOperations.kt`, wired in `ReaktorKernel.kt`; MCP stdio in `KernelAgentMcpHost.kt` |
| CLI face | `bestbuds/targets/reaktorCli/src/main/kotlin/ai/bestbuds/reaktor/cli/KernelCli.kt`, tests `KernelCliTest.kt` (5, one over the real owner wire) |

## Log

- **21 Oct 2026, Codex.** K3 scoped BuildOption projection is qualified and pushed in dfcf03ee/29233fe4: 16 existing framework cases, 433 kernel, 232 tooling and 16 CLI checks pass (12 existing skips total). Installed selected execution passes five tests with exact property/task plan and effective/default/source receipt metadata; CLI/MCP schemas agree. Source attestation is Partial. Public output options are being qualified next; the full K1/K3 package and 23-checkpoint status remain open.

- 21 Oct 2026 — K3 installed request-context and immediate-plan follow-up qualified and pushed: Reaktor `ade8deef` / BestBuds `bfd5de2a`. The planner exports under the actual request context before sealing; immediate non-approved execution reuses its prepared plan, while approved execution still re-prepares and checks its reviewed fingerprint. Existing checks pass: 433 kernel cases (11 existing skips), 232 tooling cases (1 existing skip), 16 CLI cases and the kernel boundary. After priming the model with a different shell PATH/options, the installed CLI's first request succeeds without retry: `run-1792556166597-1`, five selected tests, zero failures, exact `--tests` in the sealed plan and correlated receipt. Coverage remains Partial for source identity. Proof: `build/dependeasy/receipts/qualification/wp13/k3-request-context-plan-20261021-v3-proof.json`. Full BuildOption projection, affected execution, parameterized aliases and K7 remain open; no provider write or approval bypass.

- **14 Oct.** Plan written. Bugs fixed: `provider.refresh` sent `id` instead of `provider`; `workspace serve` rejected `--connector` and `--tunnel-origin`. Domain 1 done, 6 tests green, plus the 23 door tests. BestBuds kernel serves `build.*`, `run.*` and the adapted reads through `ReaktorKernel.invoke` and the owner's `kernel` tool; 4 CLI tests green. Backup of the uncommitted kernel and CLI work before editing: `~/.reaktor/backups/kernel-cli-wip-20261014-2143.tgz`. Coordination: Codex's dependeasy run broke `:exportBuildModel` at 21:48 and fixed it minutes later; `Main.kt` is left to Codex until WP11 is committed.
- **14 Oct, later.** `catalog()` is now cheap and `describe()` lazy, so listing never starts the agent workspace. Engineering operations bridged through `rawOperation` with their existing schemas. The MCP stdio host serves `kernel` with the seat. End-to-end test: CLI and MCP reach the kernel through the authenticated owner, and the call log records seats `cli` and `claude`.
- **15 Oct.** Handoff to Codex written: `kernel-handoff.md`. It has work packages K0–K12, starting after dependeasy WP11–WP18, and K0 lands the uncommitted slice right after WP11. Its §3 raises the root build scripts to a story altitude: `product`, `verify(parts)`, `delivery`. `dependeasy-api.md` §3 item 11 and §4.4 were revised to match, so WP12 and WP13 build to it directly.

- **15 Oct, Codex.** K0 is committed and pushed: Reaktor d761f9c9 + 7d083796, BestBuds ee9615b8 + 7e7576b6. Framework/product module gates pass, including CLI/MCP through the workspace owner; exact counts and qualification limits are in kernel-handoff K0 log and qualification/k0. The owner approved the five graph metadata fields; other graph/telemetry/cloud/app work and the staged PDF remain untouched. WP12/WP13 story API follows before the remaining kernel packages.
