# Reaktor token usage and productive throughput

Historical landscape, accounting implementation and evidence, 9 October 2026, with implementation status updated 10 October. The [engineering tools plan](engineering-tools-plan.md) is the authoritative execution roadmap; this document is supporting analysis. The build chat supplied its completion handoff, and the user-authorized first invocation/CLI startup slice has begun. The build owner's updated contract confines active rollout to Reaktor, BestBuds and Manna, and consolidates all shared build tooling in Dependeasy. Earlier configuration/build observations remain historical evidence; use the plan's final handoff record and fresh qualification for current build facts. Proposed A2A/provider/ROI mechanisms remain gated; no token-saving result is inferred from infrastructure tests.

Implementation plan after worker testing and Hangar integration review, 9 October 2026. Local Codex/Claude usage reporting is implemented in `reaktor-conductor`; the broader code and agent layer remains proposed. The earlier foundation run passed 144 tests. This review adds live Hangar MCP probes and focused worker integration checks. One context duplication bug was fixed, and candidate identity, provider fallback and tool packaging require explicit implementation gates.

The objective is more accepted, useful work within the available allowance, with less human repair and repeated discovery. Reducing tokens is useful when it preserves or improves those outcomes. Reaktor already contains much of the required substrate; the largest opportunity is to make daily agent work use its context, evidence and continuation mechanisms consistently.

## What the attached analysis establishes

The source is the local `Reaktor Token-RoI.html`, dated 7 October, and its companion [agent efficiency guide](../../bestbuds/targets/reaktorWeb/docusaurus/docs/reaktor-agent-efficiency.md). Its transcript analysis supports three strong conclusions:

1. **Request count and context residency dominate.** Reported average input was about 142K per Codex response and 433K per Claude response. Context introduced early can be sent dozens or hundreds of times. Removing a redundant round trip can therefore save more than shortening one command's output.
2. **Rediscovery is substantial.** The report classified 31–63% of responses as exploration, found 56% of Codex file reads repeated within a session, and found 34% of post-compaction reads revisited previously read files. Reading and investigation are necessary; the target is avoidable repetition, unfocused searches and information that could be retrieved more precisely.
3. **Delegation has a real aggregate cost.** In the reported Claude sample, children consumed 21.9B input tokens versus 16.2B for parents. A child's final context size is not its cumulative usage. Judge delegation using parent plus all children and accepted results.

The useful approximation within a context window is:

`input ≈ requests × initial prefix + Σ(new context at request j × later requests retaining it)`

With steady context growth, the second term grows roughly quadratically with request count. Actual cache pricing, compaction and harness retention change the cost. This is an explanation of the mechanism, not an exact subscription allowance formula.

The following qualifications change how the report should drive implementation:

- **Commits are a weak outcome proxy.** Squashing, splitting commits, reverts and unrelated work change tokens per commit without changing productivity. The reported 40M input and 119 responses per commit are useful warning signals, not the success metric.
- **9–11% edit responses does not mean the rest is waste.** Design, review, investigation and necessary verification also produce value. Track their contribution and avoidable repeats.
- **The compaction replay is an experimental bound.** The proposed 200K ceiling's estimated cost of 56% excludes lost detail, extra reads and repair. A smaller window can also change cache behavior. It is not evidence that changing one setting will deliver 44% savings.
- **Character attribution is an estimate.** The measured 4.1 and 2.4 characters per token describe that dataset. Keep provider-reported token totals separate from estimated tool/message attribution.
- **API price ratios are not subscription weights.** Preserve fresh input, cache reads, cache writes, output and reasoning separately. Do not label an API-equivalent dollar estimate as actual allowance consumption.
- **A zero tool-call count demonstrates adoption gaps.** It does not establish that the underlying capability is absent or that every file read could be replaced by a graph query.

## Current landscape

The configuration and code have moved since the report. These observations were checked on 9 October; future runs should refresh them selectively.

| Area | What exists | Remaining gap and implication |
|---|---|---|
| Usage | `AgentUsage`, `UsageSummary`, cumulative-to-turn deltas; local Claude budget hooks | Cross-harness request reporting now exists. Task outcomes, tool attribution and incremental scans remain to be connected. |
| Context | `ContextPacket`, visibility and size limits in `ContextCompiler`; local text/semantic retrieval in `AgentLocalContext` | Compile a task-specific selection with current provenance. Adding another vector store is unnecessary for the first experiment. |
| Continuation | Content-addressed `AgentCheckpoints`, workflow source revisions and versioned runbooks | Resuming workflow state does not automatically supply a sufficient coding handoff. Connect decisions, source changes and verification evidence to continuation. |
| Evidence | `AgentCandidate`, `AgentFinding`, `AgentTaskEvidence`, candidate checks and artifact pages | Reuse results only for the matching candidate and check inputs. Display unresolved findings and previous failed attempts before repeating investigation. |
| Code intelligence | `KotlinSourceIndex`, a discoverable shared Kotlin LSP, definition/hover/diagnostics interface | The index covers top-level types. The current interface does not expose references or symbol outlines. A shell-facing declaration/cone tool needs explicit additions and honest partial results. |
| Verification | Existing check reducers; BestBuds `WorkspaceTestResults` parses Gradle and Maestro XML | Connect typed results to a source-bound receipt and an explicit selection of required checks. Existing `workspace verify` verifies provider access, not product builds/tests. |
| Instructions | Reaktor root instructions are about 5.7KB; BestBuds instructions about 52KB | Keep a short mandatory map and load relevant rules by section. Preserve the design bar and dependency boundaries when reorganizing it. |
| Context settings | Codex compact threshold is configured to 180K; Claude `autoCompactWindow` is configured to 1M | Codex already has a lower threshold than the report's proposed change. Installed Claude Code 2.1.289 supports a per-launch compaction experiment; measure its effect before changing defaults. |
| MCP access | Reaktor workspace configuration is mounted in Reaktor; BestBuds has Claude MCP configuration | BestBuds still lacks `.codex/config.toml`. Tool availability needs a smoke check in the actual harness, followed by useful task instructions. |
| Hangar/kernel access | Hangar already serves read-only MCP and consumes MCP; kernel reads include source declarations, configured graphs and retained checks | Reuse these projections. Desktop selection lacks a source revision in the live probe; desktop discovery and door fallback need stronger scope/schema compatibility. |
| Module-owned tools | Conductor already has a Python local-context tool alongside Kotlin; `agentDist` currently packages JVM jars and launcher | Code and Conductor should own executable tools/recipes as well as Kotlin APIs. Include assets, runtime requirements and direct invocation in the distribution. |
| CLI/build/kernel | Legacy framework CLI guesses script/tasks; BestBuds has a thin kernel CLI and Conductor has a separate agent CLI; Dependeasy has declared DAG/artifacts | Redesign `reaktor` around authoritative evaluated build facts and shared workspace operations. Current embedded hosts share code but separate operational state. |
| Old configuration failures | The reported dead Graphify hook and dead Firebase command are absent from the inspected configuration | Do not spend another implementation pass repairing these stale findings. |
| Build routing | Configured wrappers route according to local capacity; worker outputs and test reports return automatically | Preserve this route. `remote-dev.project_at` recognizes configured workspace roots; arbitrary external worktrees need routing support before being used for worker builds. |
| Budgets | `AgentBudget` has timeout and optional dollar cost; local Claude budget tooling already exists | Native allowance and request/context budgets are not equivalent to dollars. Extend existing ownership and reporting rather than creating a competing budget system. |

Relevant implementation owners are:

- [Usage](../reaktor-conductor/src/commonMain/kotlin/dev/shibasis/reaktor/conductor/UsageSummary.kt) and [context compilation](../reaktor-conductor/src/commonMain/kotlin/dev/shibasis/reaktor/conductor/ContextCompiler.kt).
- [Evidence](../reaktor-conductor/src/commonMain/kotlin/dev/shibasis/reaktor/conductor/workspace/AgentEvidence.kt) and [checkpoints](../reaktor-conductor/src/jvmMain/kotlin/dev/shibasis/reaktor/conductor/workspace/AgentCheckpoints.kt).
- [Code intelligence contract](../reaktor-code/src/commonMain/kotlin/dev/shibasis/reaktor/code/CodeIntelligence.kt) and [Kotlin LSP owner](../reaktor-tooling/src/jvmMain/kotlin/dev/shibasis/reaktor/tooling/lsp/KotlinLanguageServer.kt).
- [XML result reader](../../bestbuds/modules/kernel/src/main/kotlin/ai/bestbuds/reaktor/workbench/testing/WorkspaceTestResults.kt) and [build routing](../../tools/remote-dev).

### External platform implications

OpenAI documents the [compaction threshold and configuration controls](https://developers.openai.com/codex/config-reference/). [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching) depends on matching prefixes, so keep stable instructions and tool definitions ahead of changing task data. Measure cache behavior alongside context reduction.

Claude's [cost guidance](https://code.claude.com/docs/en/costs) recommends clearing unrelated tasks, compacting with instructions about what to preserve, focused delegation, and code intelligence. Its deferred tool discovery also means that replacing native harness behavior with a gateway can increase loaded context. A gateway is justified by a concrete capability or measured end-to-end benefit.

OpenAI's [prompt and skill guidance](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra) supports progressive disclosure and context-dependent document reading. Retaining every historical instruction and requiring every check for every change both have recurring cost.

These sources support the mechanisms. The functional tests below validate existing foundations; accepted-task savings still require the matched pilot.

## What the additional worker tests established

The final run was `20261009-162309-reaktor` on `m1-worker`. Gradle completed successfully in 45 seconds; output transfer also completed with exit 0. The requested headless boundary check and `agentDist` build passed.

| Evidence | Result | What it establishes |
|---|---|---|
| Conductor regression | 121 tests in 20 suites, no failures/errors/skips | Usage, context, parser, checks, evidence, task scope, queue, collaboration and workflow/recovery behavior in the tested fixtures |
| Tooling regression | 23 tests in 4 suites, no failures/errors/skips | Source index, revision and digest-cache behavior; LSP adapter fixture/error paths |
| Usage CLI integration | 10 checks passed, 0.295 seconds | Parent/child separation, unknown usage, inclusive caches, scope/date filters, copied/archive deduplication and protected metadata exports |
| Usage CLI scale | 20,000 unique requests; 97,728,770 fixture bytes; 64 MiB maximum JVM heap; 0.792 seconds, repeat 0.616 seconds | Streaming parsing and deduplication of mixed, copied histories; malformed lines retained as a diagnostic; prompt content absent from the export |
| CLI rejection cases | 7 cases passed | Invalid/missing/duplicate options and existing output files cannot silently overwrite evidence |
| Artifact routing | 20 cases passed | Task options do not force JVM tests into all-platform artifact transfer; mixed native/Android tasks retain conservative routing |
| Candidate invalidation probe | Small same-size source edit invalidated; large same-size dependency edit did not | Existing candidate completeness is insufficient as the sole precondition for automatic cache reuse or source edits |

All these executions made **zero real model calls**. The initial 26-test run found a repeated peer event rendered twice in the prompt. `DefaultContextCompiler` now deduplicates visible peer events by ID; the final run includes that regression and six additional usage-history cases plus three additional context cases.

The candidate probe changed one byte in a 1,000,001-byte dependency while preserving size. Its candidate ID remained unchanged and `complete` remained true. This is an observed limitation of size-only fingerprinting, not a collision in the content digest. Before caching a check or applying a patch, strengthen the identities of that operation's relevant inputs. Hash large relevant files lazily or retain immutable artifact content digests; avoid rehashing the entire workspace for every query. Reuse the existing digest-cache machinery where its guarantees fit.

The LSP suites did not demonstrate a live Kotlin semantic server across KMP targets. Graphify and ast-grep were not installed or executed on the worker. No product UI, real harness continuation or subscription savings experiment was performed. These remain explicit adoption gates.

Machine-readable evidence: [further-test report](/Users/ovd/dev/tmp/token-roi-further-tests-20261009.json), [worker regression](/Users/ovd/dev/tmp/token-roi-worker-regression-20261009.json), [candidate probe](/Users/ovd/dev/tmp/token-roi-candidate-probe-20261009.json), [scale test](/Users/ovd/dev/tmp/token-roi-cli-stress-20261009.json), and [build receipt](/Users/ovd/dev/.remote-dev/logs/20261009-162309-reaktor.json).

### Hangar and tool-ownership integration review

The focused follow-up exercised the existing live Hangar MCP and source-audited its kernel, Conductor and door connections. It also ran 37 existing tests in seven selected suites on `m1-worker`, all passing with no failures/errors/skips. The code/tooling and headless kernel boundary checks passed. This is separate evidence from the earlier 144-test run.

| Evidence | Result and practical limit |
|---|---|
| Live graph description | Reports source/configuration truth for workers, stores and servers; execution unavailable on this read surface. This is not a general Kotlin reference graph. |
| Live source declaration | Resolved `dev.shibasis.reaktor.code.CodeIntelligence` in the sibling Reaktor checkout with one bounded source excerpt. |
| Live missing subject and run | Missing subject returned zero results with `subjectResolved=false`; an unknown run returned an explicit error. No successful evidence was fabricated. |
| Two live selection reads | Same selection version/context/subject; workspace BestBuds, environment dev. Source revision and selected subject were null, so a source-bound handoff is unresolved. |
| Door and loopback server suites | 13 tests cover current provider discovery/routing, read fallback, lazy startup, retained snapshots and loopback protocol behavior. They do not prove new schema/workspace compatibility gates. |
| Kernel and desktop graph-session suites | 24 tests cover scoped graph reads, retained check diagnostics, read-only planning, MCP client disposal and graph-session ownership/headless registration. They do not constitute product UI validation. |

Three source findings change implementation: the current CLI connection uses MCP-shaped calls; `agentDist` does not yet package module-owned script/recipe assets; and desktop name probing plus read-only/name-based fallback does not establish schema/workspace/environment compatibility. Selection-derived Conductor context is already partial and cannot substitute for strong source identity. Existing source/check reads and stores should be extended instead of duplicated.

The [focused integration audit](/Users/ovd/dev/tmp/code-agent-integration-audit-20261009.json) records probes, source anchors, test suites and receipts. No additional native agent/model execution was launched by these checks. Actual Claude discovery, native cross-harness handoff, a workflow with MCP disabled, live Kotlin/KMP semantics, non-Kotlin provider coverage and measured token savings remain untested adoption cases.

## Shared ownership across Reaktor+

Make `reaktor-code` and `reaktor-conductor` shared capability and tooling packages used across Reaktor+, following the practical model of `reaktor-tooling`. They contain the useful tools, recipes and integrations, alongside Kotlin contracts and APIs:

| Owner | Responsibilities |
|---|---|
| `reaktor-code` | Source/compilation identity; executable query/index tools, transformation recipes and guards; bounded results/plans; language and Kotlin semantic integrations |
| `reaktor-conductor` | Agent-facing context, usage, continuation and native harness tools; task association, budgets, durable jobs, authority, checks, evidence and recovery |
| `reaktor-tooling` | Generic external process/provider infrastructure, connections, transports, MCP door and device/cloud mechanics; transport-only build adapters and existing source/LSP providers reused during migration |
| Dependeasy | All shared build tooling: declarative/toolchain contracts, artifact graph, generation, compiler/bundler integration, packaging, build verification and CI/local-worker policy; consumers delegate |
| Existing graph host, CLI, MCP and Hangar | Register capabilities and project supported authorized subsets; Hangar supplies its existing MCP reads and optional desktop context |

Today tooling depends on code, and Conductor consumes tooling. Keep that dependency direction and platform-safe common payloads. Module-owned scripts, recipes and executables do not require a reverse code-to-tooling dependency: existing process owners can launch them and expose their capabilities. Optional language engines stay isolated from common types and UI; audit classpaths before relocation. Bulk source facts are versioned indexed records or overlays, rather than an active service node for every symbol. No new graph database, orchestration platform or universal plugin system is needed for the first slices.

Tools can be Kotlin, Python, TypeScript, shell or established native engines. Their usefulness and proven language coverage determine the implementation. Kotlin integrations provide stronger compilation, identity and graph context without making every agent import Kotlin or restricting code work to Kotlin. Agents need directly usable commands, help, bounded outputs and handles. Package tool assets and declare versioned runtime prerequisites; do not depend on an arbitrary current directory or repeated per-task installation.

Source relationships, declared Reaktor connections and runtime observations remain distinct. Each result states its workspace and compilation scope, provider/version, derivation inputs, evidence strength and completeness. An unsupported target is explicit. Keep semantic resolution authoritative for overload/receiver-dependent changes; a syntax match cannot establish that authority.

### Unified API design requirement

The [Reaktor code and agent system HLD](code-agent-hld.md) defines the architecture and proposed API contract. `reaktor-code` and `reaktor-conductor` form one coherent system: code tools and their semantics belong to code; agent tools, task continuity, policy, execution and evidence belong to Conductor. Tooling supplies shared infrastructure and current providers; it is not the compulsory home for every new specialist tool.

The [CLI, Dependeasy and Hangar HLD](cli-kernel-hld.md) replaces the obsolete terminal architecture in this plan. Stable build/check/dev verbs select declared capabilities; code/change/task/run/artifact operations project the same shared semantics. Dependeasy's normalized target/artifact plan supplies build facts. The workspace kernel owns policy, operation lifecycle and receipts. CLI and Hangar share that owner instead of duplicating task inference or opening implicitly independent operational state. Bootstrap build tooling remains independent of product runtime graphs.

Kotlin callers, CLI, MCP, Claude and Codex must use the same operation definitions, schemas, validation, scope and effect semantics. Both harnesses can choose MCP, shell/CLI or programmatic helpers. MCP is a projection; shared dispatch and policy must work without an MCP handler or session. Preserve existing graph identities and typed ports. Avoid a parallel agent API, transport-specific business logic, or a command whose meaning changes with the caller. Keep the common workflow composable: open task → obtain bounded context → query code → plan change → apply matching plan → run required checks → retain continuation.

Hangar already has a read-only MCP server and an MCP inspection client. Compose shared code/context/check read capabilities into its supported surface, preserving desktop-only observations and their lifecycle. Headless kernel reads remain usable when Hangar is closed. Workspace task controls and source mutations retain authenticated authority; they do not become unauthenticated desktop read tools. Preserve selection/workspace/source/environment provenance and mark missing source mappings partial. Require compatible schema/version, workspace and environment before provider fallback; current name/read-only matching is insufficient.

The current workspace CLI invokes an MCP-shaped `tools/call` bridge internally. Extract shared operation invocation beneath that handler, then make CLI and typed clients direct consumers; retain the bridge for compatibility. Ordinary shell, editor and build tools remain useful. Capture their observed source/evidence changes under the same candidate/task ownership rather than requiring every action to pass through MCP.

Before implementation, review the contracts against the HLD's acceptance matrix: ambiguity and unsupported capabilities remain explicit; writes require exact plan/input identity; results and diagnostics are bounded; checks name executed coverage; retries return the same durable operation; both native harnesses can continue the same task. The operation names in the HLD are proposed and do not imply shipped commands.

### Semantic substrate and immediate ROI

The additional architecture notes strengthen the shared substrate: versioned source/syntax/semantic/build/Reaktor/verification/runtime/change overlays, capability-aware query planning, bounded impact views and normalized findings. Reaktor owns their identities, provenance, lifecycle and task use. Specialist engines own parsing, resolution, transformations and checks; their compiler/PSI objects stay private. Retain useful indexed facts, compute focused views on demand and avoid materializing the entire AST as active graph nodes.

Keep relation kind, evidence strength and selection policy separate. A resolved reference, declared build dependency and observed runtime call have different meanings. An impact cone states its inclusion reasons and omissions; it is not a complete affected-test oracle. Unknown targets, skipped rules and parser omissions remain visible in findings and check receipts.

| Track | Near-term deliverable | Expansion gate |
|---|---|---|
| Immediate ROI | Task association, short continuation, bounded failure/context packets, reusable check receipts and aggregate native usage | Real Codex/Claude work with fewer repeated reads/checks and no additional repair or omitted acceptance |
| Semantic substrate | Strong relevant-input identities, incremental syntax/build facts, scoped queries and shared non-MCP dispatch | Demonstrated compilation-specific semantics, bounded impact and one profitable deterministic recipe |

Both tracks use the same task/candidate/compilation identities, findings and operation contracts. Do not build separate ledger, graph and codemod control planes. Start immediate adoption with existing facts while adding the semantic precision each consumer requires. The HLD supplies provider choices and boundaries; it does not require installing every listed engine.

## Define return on investment

Use an **accepted task** as the outcome: a requested behavior or decision meets its stated acceptance criteria, has the required evidence, and is accepted by the owner. A reopened task remains linked to the original outcome so repair cost is visible.

Record three dimensions rather than hiding them in one score:

| Dimension | Measure |
|---|---|
| Productive throughput | Accepted tasks per allowance window, grouped by task class and scope |
| Consumption | Reported input/cache/output tokens, responses, model/effort and all child runs per task; observed account allowance separately |
| Time and quality | Elapsed time, active human time, first-pass acceptance, required-check coverage and subsequent reopening/regressions |

An account-wide allowance snapshot can reflect other concurrent work. Do not assign its change to one task without a controlled window. Missing metrics remain unknown. Neither fewer tests nor more fragmented commits may count as an efficiency improvement.

For diagnosis, add median/p95 request input, file rereads, repeated checks on unchanged inputs, overlap between active tasks, tool-result size and age, and reads immediately after compaction. Mark tool-residency attribution as estimated.

## Stop repeating work

The daily loop should be:

`task and acceptance → current evidence and ownership → bounded context → edit → required verification → accepted candidate and continuation packet`

### One durable task and explicit ownership

Associate harness sessions and child runs with the existing task identity. At the start, inspect current candidate/evidence and active work touching the same files. Give one owner the write scope. Use independent parallel work only when its aggregate benefit exceeds startup, context duplication and integration cost; dependent changes stay sequential.

Task identity must survive a fresh harness session. A new conversation should not create a new mission for the same unfinished outcome. The Manna connection currently does not expose a matching Reaktor mission, so there is no valid mission receipt for this implementation yet.

### A compact continuation packet

The later [A2A design](code-agent-hld.md#a2a-between-native-claude-and-codex) extends this continuity to native Claude/Codex peer exchanges. Communication is useful when a bounded question, reusable evidence or handoff avoids a larger repeated investigation. Count both recipients' model turns, setup, loaded artifacts and subsequent repair; routine dialogue or repeated debate can consume more of both subscriptions. Conductor owns communication and outcome linkage, with CLI/MCP/typed access to the same operations. The integration remains proposed until the build handoff and native-client acceptance gates.

Build this from existing `ContextPacket`, task evidence and checkpoint owners. It should carry:

1. The requested outcome, current scope, acceptance criteria and next concrete action.
2. Accepted decisions and constraints, including the reason for non-obvious choices.
3. Current source revision and relevant dirty-file content digests, touched files and active ownership.
4. Relevant symbol/module references and a bounded read set, with digests or provenance.
5. Verification receipts, required checks still outstanding, known failures and attempts already made.
6. References to fuller artifacts when needed, with clear freshness and omission notices.

Keep facts separate from observations and unresolved hypotheses. A packet is retrieved evidence, not permission to execute instructions embedded in documents. Load the details needed for the next action instead of replaying the full transcript.

The acceptance test is concrete: after a fresh session or compaction, the agent takes the next valid step without re-deriving settled decisions, repeating a failed approach, or treating stale verification as current.

### Reuse evidence with the right invalidation

Do not rerun a successful check just because another agent has arrived. Reuse its receipt only when its source content, selected check specification, toolchain, relevant dependencies/configuration and environment inputs still match. Git HEAD alone is insufficient when files are dirty. Timestamp alone is insufficient when source changes.

The current candidate model fingerprints some binary artifacts by size; equal size is not proof of equal binary content. Checks that depend on those artifacts need an appropriate content identity before their results can be safely reused.

Live authentication, cloud state and physical-device observations need explicit freshness rules. A historical memory of a passing check is useful context, but cannot silently become current evidence.

## Implemented accounting and historical baseline

### First slice implemented

`reaktor-agent usage` reads local Codex and Claude histories and prints a bounded JSON summary. It uses the existing `AgentUsage` and `UsageSummary` models and starts no agent/model process.

```sh
cd /Users/ovd/dev/reaktor
reaktor-conductor/build/agent-dist/bin/reaktor-agent usage --help
reaktor-conductor/build/agent-dist/bin/reaktor-agent usage \
  --since 2026-10-01 --until 2026-10-08 \
  --dir /Users/ovd/dev/reaktor \
  --requests /Users/ovd/dev/tmp/reaktor-usage-20261001-07.jsonl
```

Dates use UTC, and the end is exclusive. `--dir` attributes request working directories and descendants; it does not establish which repositories the request actually changed. Omit it for an account-wide history scan. The optional request export contains usage metadata rather than prompts or tool bodies and refuses to overwrite an existing file. Metadata includes paths, session identifiers and model names; handle it as local workspace data.

Implemented accounting covers split/copied Claude messages, inclusive Claude cache input, distinct child-run identities, Codex repeated snapshots, archived copies, fork replay/inherited counters, date boundaries and unknown fields. Weekly allowance reports the latest observed account snapshot per limit within the selected interval, independently of the directory filter.

Validation now includes 17 collector tests, 2 usage-summary tests and 4 harness-session tests within the 144-test worker run, plus the rebuilt public CLI integration and scale fixtures above. `agentDist` also builds through the configured worker route. The implementation adds no dependency, module or persistent schema. It has not been installed over the shared user distribution.

This is a **request-accounting slice**, not the completed ROI ledger. It scans full local history each time; incremental checkpoints, task association, tool mix/residency, effort aggregation and accepted-outcome metrics remain future work.

### Local audit from the first slice

For `[2025-12-01T00:00:00Z, 2026-10-08T00:00:00Z)`, without a directory filter, the collector reported:

| Harness group | Sessions with selected requests | Responses | Reported input | Mean input per response |
|---|---:|---:|---:|---:|
| Claude parent | 114 | 32,495 | 15.203B | 467,854 |
| Claude child | 519 | 58,709 | 21.732B | 370,160 |
| Codex parent | 225 | 102,839 | 14.760B | 143,523 |
| Codex child | 539 | 36,861 | 4.248B | 115,248 |

Total: 230,904 responses, 55.943B reported input and 160.899M reported output. Input and output were present for all selected records; reasoning output was unknown for 6,235 responses. No malformed or unreadable files were reported. The scan read 1,545 files, took about 24.6 seconds, and printed about 2.7KB of summary JSON.

These are a provisional collector baseline, not a reproduction of the attachment's 190K/53B sample. The report used 805 Codex and 487 Claude files; the current scan includes archived Codex history and a larger Claude inventory. File inventory, cutoff/timezone and deduplication rules must be held constant for a causal comparison. Fixture validation does not independently audit every historical provider record.

The larger sample still supports the same practical priorities: large recurring context and substantial aggregate child cost. Do not turn these numbers into an allowance or dollar claim.

The local summary is `/Users/ovd/dev/tmp/reaktor-token-roi-baseline-v1-20261009.json`; the metadata export is `/Users/ovd/dev/tmp/reaktor-token-roi-requests-v1-20261009.jsonl`. They are local generated artifacts, not committed source.

## Execution roadmap

The single [engineering tools execution plan](engineering-tools-plan.md) owns sequencing, build-upgrade handoff, budgets, provider decisions and adoption gates for CLI, code, Conductor and tooling. It supersedes the former slices in this document. The build chat's 10 October completion handoff satisfied the user's start condition; the selected framework target/export and remaining product failures are recorded at Gate 0. The first invocation and CLI startup slice is underway. Native A2A, provider expansion and economic acceptance remain distinct gates.

The [code/agent HLD](code-agent-hld.md) and [CLI/kernel HLD](cli-kernel-hld.md) remain architecture references. The historical evidence above is retained, including the implemented accounting slice; no controlled task-saving result has yet been measured.

## Concrete tooling improvements

### A bounded change context query

Make one query answer the recurring preparation questions: where the declaration lives, its signature, direct references, nearby implementations, relevant module rules, known findings and applicable checks. Include short excerpts, source identities, omission notices and a caller-supplied output budget. Return additional pages only when requested.

The current `source_declaration` tool resolves Kotlin types. `graph_query` resolves workers, stores and servers. Neither supplies a general Kotlin function reference graph. Extend the existing source/code intelligence owners and link source facts to Reaktor definition identities where applicable. Preserve the distinction between source relationships, declared system dependencies and observed runtime facts.

There is already a generic `workspace call --tool ... --request ...` CLI transport, currently implemented through the MCP bridge. Reuse its useful transport/connection machinery while separating shared operation dispatch from MCP. CLI conveniences, MCP tools and typed clients must share the query implementation; CLI/CI must also work without MCP. Additional remote protocols need a concrete consumer before being added.

Move a small version of this query earlier in the pilot: declaration, outline and scoped references can earn value before a complete project graph or affected-test selector exists. Unknown test coverage must remain unknown.

### Codemods for the next repeated migration

Good first recipes include a moved API/import with known identity, a repeated old-call/new-call mapping, or a design-token substitution whose owning component makes the mapping unambiguous. A literal value alone cannot choose between several tokens with that value. Architectural redesign and state ownership still require reasoning.

A recipe should produce a dry-run plan containing its version, input digests, intended edits, skipped sites, reasons and required checks. Apply only against matching source; a second application should make no further changes. Exercise overloads, import aliases, comments/strings and intentional no-change examples. Preserve a reviewable diff and turn ambiguous cases into existing findings.

Evaluate existing machinery before implementing a generic rewrite engine:

- [ast-grep](https://ast-grep.github.io/reference/languages.html) supports Kotlin and is a candidate for bounded syntactic rules.
- [OpenRewrite's Kotlin recipes](https://docs.openrewrite.org/authoring-recipes/writing-kotlin-recipes) support before/after patterns and visitors; pilot build/toolchain compatibility and setup cost on one recipe.
- [Kotlin Analysis API symbol resolution](https://kotlin.github.io/analysis-api/resolving-symbols.html) can distinguish resolved targets and ambiguity. Use semantic resolution where a transformation depends on receiver types or overloads.

The recipe is worthwhile when its setup plus exceptional cases costs less than manual application across actual sites. Measure recipe creation as part of the first migration's cost; later reuse is an additional benefit.

### Provider decision

The canonical plan's Joern/SCIP review and provider matrix replace the earlier standalone Graphify pilot. Use existing lookup/LSP first, qualify compilation-specific semantic navigation and SCIP artifacts for a measured gap, and admit Joern on demand for deeper flow questions. Graphify, CodeGraph and Serena remain alternatives subject to the same correctness, freshness and total-cost gates. No provider pilot has been executed by this consolidation.

### Reduce recurring review and repair

Extend existing guards such as `SurfaceRetirementTest`, `DesignArchitectureTest` and `SafeDeleteGuardTest` when repeated findings justify another deterministic rule. Report precise, bounded violations with meaningful exclusions. These prevent the same issue from being generated and reviewed repeatedly.

Bundle a failure's diagnostic, small source excerpt, implicated declaration and exact next check into a bounded result. Retrieve previous unresolved findings and attempted fixes for the same current source before repeating investigation. Store decisions with reasons and validity conditions, rather than preserving every intermediate thought.

For runtime investigations, use structured elements, request records and diagnostics to inspect behavior, and visual evidence when appearance matters. Generate stable mechanical contracts from their existing owners where repeated handwritten setup is measured. Reuse unchanged verification evidence under the invalidation rules above.

## Continuation for this work

Resume from the checklist in the [single execution plan](engineering-tools-plan.md) after the build chat's completion handoff under the user's conditional start authorization. Reconcile the completed build handoff, then implement continuity, strong relevant-input identity, shared dispatch and packaged tools through the first unmet gate. Refresh only facts that the slice depends on; retain these audits as historical evidence instead of repeating the landscape survey.
