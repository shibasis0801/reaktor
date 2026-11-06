# One workflow engine on reaktor-work and reaktor-graph

*Plan of 6 November 2026, rewritten after your answers the same day. The traversal in section 7 is built, verified and committed in `d6da497a7`. Phase 1 is built and committed in `6203cfe9a` on the evening of 6 November: the suite passes on all four platforms it runs on, and all six hosts pass their whole platform proofs, the Android reboot included (section 4). Phase 2 is built and checked on the evening of 6 November. Its engine half, the steps in graph-runtime and reaktor-work on top of them, is committed; the Hangar overlay and the Blueprint fixes are not, because they are built on another session's uncommitted Blueprint edits (Phase 2 below). Phase 3 has started with one BestBuds change; the rest of it waits on other sessions' unfinished work and on one decision of yours, whether the BestBuds flows may stop being one transaction (Phase 3 below).*

## 1. Plan

Diagnosis: reaktor+ has 19 workflow-like systems, and 11 of them should run on one engine. reaktor-work is the base for that engine, but it is unfinished: 482 of its 1,569 lines of main code have no user outside its own tests, and only its Android host has run work on a device.

Your answers settled seven things:

- The server host is Quartz, inside the Spring Boot server. Quarkus is out.
- Control flow lives in the graph. The engine records each step's result and never replays code.
- CompanyOS is out of scope.
- The order is reaktor-work first, then reaktor-graph, then the migration.
- Agent work plans (`WorkPlan`, written with `plan.write`) stay the human checklist. A plan item can point at a run and mirror its state.
- The browser is a sixth target, with its own hosts for pages, dedicated workers and service workers.
- The code follows the reaktor coding guidelines: one package per concept, a hierarchy a reader can follow from the top package down, and names that need no comments.

### Phase 1: reaktor-work on Android, iOS, the browser, Workers, the server and the desktop

Phase 1 makes reaktor-work the engine and uses the graph exactly as it is today. A step is a node with a provider port for its result, and its inputs are consumer ports wired to earlier steps. Names come from `val x by step(...)`, and a condition is a value the step holds that reads only recorded outcomes. Nothing in Phase 1 needs a new graph concept.

- **Bloat out** (done, except inspection)
  - Done: `RequestDag` and `DurableWorkGraph` are deleted, with their private registries, string step ids and 64-node limit. The comment lines, `TaskManager.kt` and the Cloudflare Queues binding are gone, and the other bindings were rebuilt as the hosts below.
  - Done: `WorkStore` is whole. Its eleven operations have no default bodies, and every store implements them.
  - Partly done: inspection and control were rebuilt over the new run model, in 20 types and 402 lines, and devtools, the Cloud pane and the kernel's MCP still read them. Replacing them with the run handle, the journal and the definition's shape waits for Phase 2's run overlay, which gives those users something to read instead.
- **One run model** (done)
  - A run is a record plus its journal, and each step's result is recorded once under the run id and the step's name.
  - A step is ready when every input is recorded and each input's condition holds.
  - Effects set the retry rule. `ReadOnly` and `LiveRead` steps run again with backoff for three attempts. Any other step gets an idempotency key made from the scope, the run id and the step's name, and when an attempt is lost it either reconciles or stops in `Unknown` for an operator.
  - Waits are signals, timers and gates, and a gate is a signal with a sender requirement. `call` runs a child workflow, `each` runs one child per element and waits for all of them, and `loop(limit)` reruns a child workflow until its `next` returns null. A quorum for `each` is not built.
  - Cancellation, and a failure that no step handles, run the `undo` of finished steps in reverse order.
  - A run pins its definition digest, and a run whose definition changed is quarantined. Migrations are not built.
- **Store and journal** (done)
  - Done: scoped admission, fences, leases, checkpoints that renew the lease, and an atomic journal with acknowledged cursors on SQLite, D1 and Postgres. Due work comes from a projection of the journal that holds one wake per open run, so a drain no longer reads finished runs.
  - Done: each journal event names its transitions, `RunTransition.State` for the run and `RunTransition.Step` for each step, which map onto the kernel's `state` and `progress` run events. A change leaves the journal once every consumer's cursor has passed it and the store's retention, 7 days by default, has run out. Pruning is checked on SQLite on all four platforms, on D1 under workerd and on Postgres.
- **API** (done): typed definitions and typed run handles (section 3). A caller passes the workflow node and typed values, never a definition id or a payload string.
- **Hosts and the six-target matrix** (done)
  - The conformance suite in commonTest has 23 tests, and `ReplayConformance` adds 9 for the Workers interpreter. All 32 pass on the JVM, in Kotlin/JS, on an Android phone and on an iPhone. Workers runs the interpreter under workerd, and the desktop runs the JVM suite.
  - Platform proofs and their results are in section 4.
  - Done: request mode. `Work.execute` keeps a run in memory while it only reads and stores it once, when it ends or starts to wait. Before a step with any other effect, or a child run, the run is stored and carries on as a stored run, so a write keeps the crash guarantee it has under `start`.
- **Existing users** (done): the files outside the module moved to the new API. The BestBuds Android, iOS and browser apps build, reaktor-cloudflare's 26 JS tests pass, the engine compiles, and Hangar was reinstalled from it on the evening of 6 November. In the kernel, `KernelWorkTest` passes 3 of 3 and `NativeKernelExecutionTest` 4 of 4.
- **Exit** (done): the suite and the platform proofs pass, the Android reboot included (section 4). The README describes what exists, and section 4 records the first measurements of steps per second and wake latency. The reaktor.build page is rewritten and deployed.

### Phase 2: reaktor-graph learns to read workflows, with the same model

*Built on the evening of 6 November.* The engine half is committed: the steps, conditions, checks and `RunShape` in graph-runtime, and reaktor-work on top of them. The Graph pane's overlay, the Cloud Work map and the Blueprint fixes are not, for the reason given under the Blueprint upgrades below. Phase 2 adds only what the graph itself has to understand. Each item reuses a concept the graph already has, and no new `NodeKind` was needed.

- **Steps in graph-runtime** (done): `input`, `step`, `either`, conditions, `StepState` and `StepScope` moved from reaktor-work into graph-runtime's new package `dev.shibasis.reaktor.graph.step`, as extensions on its `Workflow`. reaktor-work keeps the waits (`signal`, `timer`), the calls (`call`, `each`, `loop`) and the executor. `activity` and `stage` stay until the BestBuds flows move in Phase 3.
- **Readiness as a relation** (done): `Ready` follows a step's inputs and the steps its condition reads. `Workflow.steps()` orders a definition with `topological(Ready)`, and the planner, the map and the checks share one `readiness` rule.
- **Definition checks** (done): `Workflow.problems()` names a step that no run can reach, a condition that can never hold and a result that nothing uses, and a cycle fails in `topological`. `WorkflowPlan` refuses a definition with a problem. Host limits stay in reaktor-work.
- **Conditions on the map** (done): `WireShape.condition` carries the outcome a wire waits on, such as "failed". On the Graph map the port that waits reads "when charge failed", and the wire's label reads "when failed" when the wire is selected or hovered.
- **A run overlay** (done): `RunShape` holds each step's state, attempt and timing, keyed by node id, and `GraphShape.run` places a run on any shape that holds its workflow. Work definitions now send their own shape, and `RunMetadata.over(shape)` builds the overlay; a host on an older build that sends no shape is drawn from its node list. Cloud Work draws the definition through the shared Graph map, with real port rows, and `workDagLayout` is deleted. "Show this run on the graph" hands a run to the Graph pane, which follows it live. Following a run lays the map out once, to give closed workflows a state column; each later step change only repaints. Studio's maps hold no workflow, so they draw no run yet, and the Deploy tab gets runs with the release workflow in Phase 3.
- **Steps on workflow cards** (done): the Graph pane folds every workflow again, and the inspector's "Show steps" opens one. A closed workflow lists, as rows, each step that has no port row of its own: its name, its effect or kind, its condition and, during a run, its state. Steps made with `activity` already show as the workflow's port rows, so each step is drawn once.
- **Checks** (done): graph-runtime's `StepGraphTest` 4 of 4; reaktor-work's 32 conformance checks on the JVM and in Node; reaktor-cloudflare's 26 JS tests; the kernel's 7; the engine's Graph and Cloud Work suites, 72 tests; the Blueprint name-tag suites, 23 tests; the graph acceptance run, 58 of 58 on six maps in both themes. graph-runtime, reaktor-work and the BestBuds app compile for Android, iOS, the browser and the JVM. `RunOverlayRenderTest` draws a closed and an opened workflow with a run on them. In Hangar's UI flows, the Cloud Work flow was brought up to date with Phase 1's runs view and now also follows a run into the Graph pane; it passes. Four Graph flows (01, 03, 15, 90) were already failing the same way on 5 November, and the feature-frames flow (09) fails the same way with folding switched off, so none of them comes from this work.

### Phase 2 and the Blueprint upgrades

*Revisited on the evening of 6 November.* Phase 2 draws runs on the Blueprint, so the fixes from the 5 November Blueprint review ([ranked list](https://claude.ai/artifact/Snc8yK4KRBVgE5GwyHpZtH)) decide how well a run reads there. Graph ports are built and committed in `d6da497a7` (`plans/graph-ports.md`). I re-checked against the tree the review fixes that Phase 2 touches:

| Review fix | State late on 6 November | Role in Phase 2 |
| --- | --- | --- |
| 1. Group names at every zoom | built, not committed: name tags draw at every zoom. Small groups place their tags first, and a tag that collides moves inside its group, beside or below its neighbour's tag, or under its group. A group whose top has scrolled off the map keeps its name pinned at the top. The acceptance run names every group in view on all six maps it draws, in both themes, at the opening, 10% and 25%, and all 58 of its checks pass | needed first, so workflow names read at the opening |
| 2. Readable context on selection | built, not committed: other cards keep at least 3:1 text contrast while one is selected (worst 5.2:1 dark and 4.5:1 light on the whole-system map) | needed first, because the overlay colours step states and a selected step must not erase its neighbours |
| 4. Open on the subject | built, not committed: a plan opens on its subject, and Cloud Work selects a run's subject, its running, waiting or failed step | extended: a run's subject is its running, waiting or blocked step |
| 6. Port rows on the four port-less maps | Cloud Work done: it draws the definition through the shared Graph map with port rows, and `workDagLayout` is deleted. The cluster-task map already had them. Query plan and Studio Navigation still join card bodies | the overlay replaced Cloud Work's map with the workflow's own shape |
| 7. Proof loop | built, not committed: `RevampGraphAcceptanceTest` renders six maps in both themes, writes a ledger and a table of checks, names any group it finds unnamed, and fails on a failing check | every change below was proved with it |
| 8. Never blank on reload | built for the Graph pane and Cloud Work: each keeps its last map while a new layout runs. The Relationships producer was not re-checked | needed, because the overlay changes with every run update |
| 10. Card text budget | not built: Work rows still read "Waits on" | independent, because Work maps plans, which stay the checklist |
| 12. Frame budget | not measured. By construction a step change only repaints: following a run lays the map out once, to add the state column | needed, because a status change must repaint without a new layout |
| The ravioli fold | built, not committed: the Graph pane folds every workflow into one card with a row per step, and "Show steps" opens one | it is the item "steps on workflow cards" above |

The other review fixes (3 packing, 5 keyboard, 9 wires, 11 toolbar, 13 search, 14 contrast and 15 inventory) do not depend on the engine and were not re-checked. Fixes 1, 2 and 4 were built on top of another session's uncommitted edits in 15 reaktor-blueprint files, the painter, the views, the graph engine and the canvas state among them, so those files can only be committed together with that session's work or hunk by hunk. The Graph pane's step rows also need that session's row support (`BlueprintCardLook.rows` and `BlueprintRowLook`), so the overlay and the Cloud Work map wait with those files.

The order inside Phase 2, all of it built except the measurement for fix 12:

1. Fix 7, the proof loop, so a picture can prove each change that follows. Graph ports, which the step rows and the depth view build on, are already committed.
2. Fixes 1 and 2, then the subject rule of fix 4.
3. Steps move into graph-runtime's `Workflow` and draw as rows on their workflow's card. That takes the Worker islands from 415 cards to about 227. Build the rows so the depth view can reuse them, since a workflow card with step rows is a collapsed graph with boundary rows.
4. Conditions label their wires.
5. The run overlay, with fixes 8 and 12 for live updates. Cloud Work, Studio and the Deploy tab draw it, and `workDagLayout` is deleted.

### Phase 3: migrate everything

*Started on the evening of 6 November.* One change is built: `OnboardingFlow` now builds each island's copy only when that island asks for it, so the social and circle-formation Workers no longer build each other's backend graph. It compiles for Workers and is not deployed, because a Workers deploy from this tree would also ship other sessions' unfinished Worker code. The rest of Phase 3 waits:

- The conductor, the kernel's operations and cluster tasks, and the Deploy tab's lifecycle all have other sessions' uncommitted edits in the files the moves below rewrite.
- The BestBuds flows are request handlers that run several stages in one Postgres transaction. As durable definitions each step would commit on its own, which is safer for a crash but drops the all-or-nothing behaviour. That is your call.
- Trimming each Worker's flow copies to its own entry points needs the flows to declare steps per island, which is the same restructuring as turning them into definitions, so it moves with them.

The order puts first the moves that exercise the most of the engine at the least risk.

1. **Conductor.** The build-review-repair runbook runs on the desktop host, which exercises agents, checks, a gate, conditions and a repair. Then `TaskGraphRuntime`'s coordinator rules (readiness, repair, limits and budgets) move into the engine and the runtime is deleted. Protocols become templates, the Hybrid seat becomes a `repeat` workflow, and the board's task-graph fields go.
2. **Kernel.** Kernel operations become runs with approvals as gates, and the kernel's run journal converges with the work journal. Cluster tasks follow.
3. **Releases.** The deploy lifecycle's 63 steps become a release workflow whose steps are the 21 Worker deploys and the server rollout, and the Deploy tab draws its runs.
4. **BestBuds.** First come the trimmed per-Worker flow copies and the lazy onboarding flow from the ravioli scan, then the 26 flows as definitions. Worker crons start runs.
5. **Manna.** The documentation export and the connector queue move once the Workers host has run BestBuds flows in production.
6. **Cleanup.** Each move deletes the old engine's code in the same change.

## 2. reaktor-work before and after Phase 1

| Part | Before: files | Lines | Users outside the module | After Phase 1 |
| --- | --- | --- | --- | --- |
| Records, store and journal | `WorkRecord`, `WorkStore`, `WorkJournal` | 377 | BestBuds app, kernel, Cloud pane, D1 test | `run` and `store`, 333 lines; the store is whole, due work comes from a journal projection, and the journal is pruned |
| Runtime and host | `WorkRuntime`, `WorkHost`, `TaskManager` | 233 | BestBuds app, kernel, desktop fixture | rewritten as `execution` (743 lines, request mode included) and `host` (61 lines) |
| Inspection and control | `WorkInspection`, `WorkInspectionSession`, `WorkControl` | 377 | devtools, Cloud pane, kernel MCP | rebuilt over the run model, 402 lines; replacement waits for Phase 2 |
| Request DAG | `RequestDag` | 93 | none | deleted |
| Durable graph | `DurableWorkGraph` | 188 | none | deleted; `definition` (416 lines) is the typed workflow surface |
| Android and iOS schedulers | `AndroidWorkScheduler`, `DarwinWorkScheduler` | 100 | BestBuds Android and iOS apps | kept and proven on a phone; iOS also drains while the app is active |
| Quartz, Cloudflare and browser bindings | `QuartzWorkScheduler`, `CloudflareWorkBindings`, `BrowserWorkScheduler` | 201 | none | rebuilt as hosts; the Workers host runs each run as a Workflows instance, and the Queues binding is gone |

Main code went from 1,569 lines, 482 of them unused, to 2,570 non-blank lines: 2,159 shared and 411 in the five platform hosts. It grew because it now holds the typed definitions, conditions, child runs, undo and six working hosts. Tests went from 616 lines and 20 JVM tests to 1,076 lines and 32 tests that run on four platforms, plus the Quartz cluster, wake latency and workerd proofs. BestBuds has three definitions, `bestbuds.device.diagnostic`, `bestbuds.notification.reconciliation` and the proof workflow `bestbuds.device.restart-check`, and Hangar's desktop host has `hangar.work.restart-check`. The BestBuds server does not use reaktor-work yet, so the Quartz host has run only in its own tests.

## 3. Package layout and the Phase 1 API

As built:

```text
dev.shibasis.reaktor.work          Work, Run, Feature.Work
├── run                            RunRecord, RunTransition, StepRecord, RunState, WorkScope
├── definition                     Durable, signal, timer, call, each, loop, WorkflowPlan
├── store                          WorkStore, ObjectWorkStore
├── execution                      the planner, the executor, attempts, outcomes, request mode, Replay, WorkScheduler
├── host                           WorkHost, LiveScheduler
├── inspection                     WorkInspector, WorkController, WorkInspectionSession
└── host.android, host.ios, host.browser, host.cloudflare, host.quartz   in their platform source sets
```

Since Phase 2, `Output`, `input`, `step`, `either`, `Condition`, `StepState` and `StepScope` live in graph-runtime's `dev.shibasis.reaktor.graph.step`, next to `Ready`, the definition checks and `RunShape`. `run` uses no other package. `definition` and `store` use `run`, `execution` uses those three, and `Work` in the root package uses all four. `host` and `inspection` build on `Work`, the graph node that owns a scope's runs and the one entry point. The desktop host is `LiveScheduler` over SQLite inside the Hangar host. Against the sketch this plan started with, `gate` became a signal with a sender requirement, `repeat` became `loop`, `either` was added, `RunEvent` became the `RunTransition`s that journal events carry, and request mode became `execute`.

```kotlin
class Onboarding(graph: Graph) : Workflow(graph, "onboarding", "Onboarding"), Durable<OnboardingRequest, Welcome> {
    override val input by input<OnboardingRequest>()
    val profile by step(input, effect = Effect.ProductionReversibleWrite, undo = { deleteProfile(it) }) { save(it) }
    val code by step(profile, effect = Effect.UnknownRemoteEffect, reconcile = { sentCode(it) }) { sendCode(it) }
    val entered by signal<CodeEntered>(timeout = 1.days, on = code.succeeded)
    val joined by step(profile, entered, effect = Effect.ProductionReversibleWrite) { profile, entered -> joinFirstCircle(profile, entered) }
    val nudged by step(profile, effect = Effect.UnknownRemoteEffect, on = entered.timedOut, reconcile = { sentReminder(it) }) { remind(it) }
    val welcome by either(joined, nudged)
    override val output = welcome
}

val run = work.start(onboarding, OnboardingRequest(user), key = "onboarding-${user.id}")
run.signal(onboarding.entered, CodeEntered(code))
val welcome = run.result()
```

- Each property names a step, and the run records the step's result under that name.
- `input` declares the workflow's input, and `output` names the step whose result is the run's result.
- `step` registers one consumer port per input and wires it to the earlier step's provider port, so `Upstream` gives the dependency graph without a second list of edges.
- `on` is a condition that the step holds, and it reads only recorded outcomes. A step whose condition can no longer hold is skipped, and `either` takes the branch that ran.
- `effect` sets the retry rule. `reconcile` answers whether a lost attempt took effect, and `undo` reverses a finished step when the run is cancelled or fails.
- A run handle offers `updates` as a flow, `record()`, `result()`, `signal()` and `cancel()`.

The conductor's build-review-repair runbook, as it would read in Phase 3. `agent`, `check` and `decided` do not exist yet, and they arrive with the conductor's move:

```kotlin
class BuildReviewRepair(graph: Graph, builder: AgentSpec, reviewer: AgentSpec, check: CheckId) : Workflow(graph, "build-review-repair", "Build, review, repair"), Durable<Task, Decision> {
    override val input by input<Task>()
    val build by agent(builder, input)
    val verify by check(check, build)
    val review by agent(reviewer, build, verify, contract = Decision)
    val repair by agent(builder, review, on = review.decided(Repair) or verify.failed)
    val recheck by check(check, repair)
    val rereview by agent(reviewer, repair, recheck, contract = Decision)
    val accept by signal<Decision>(sender = Reviewers, on = review.decided(Pass) or rereview.decided(Pass))
    override val output = accept
}
```

The runbook allows one repair, so the graph has no cycle. A workflow that repairs until it passes wraps a child workflow in `loop(limit = n)`, so the bound is part of the definition.

## 4. Hosts and proofs

| Target | Wakeups and waits, as built | Store | Platform proof | Result on 6 November |
| --- | --- | --- | --- | --- |
| Android | WorkManager unique one-time work at the next wake, drained by `ReaktorDrainWorker` | SQLite | on a phone, a run with a timer survives a force-stop and a reboot | both pass on the Samsung SM-S918B: after a force-stop the run finishes about 2.5 s after relaunch; after a reboot, once the phone is unlocked, the app finishes it in a background launch about 20 s later |
| iOS | `LiveScheduler` while the app is active, and `BGProcessingTask` in the background | SQLite | on an iPhone, a run resumes in a background launch and after the app is killed | passes on the iPhone 14 Pro: after a kill, the run finishes about 1 s after relaunch; in a second proof the app was killed while its run waited on a timer, iOS launched the app in the background, as the app recorded at launch, and the run finished in that launch 5 min after the timer fell due |
| Browser | a page timer under a Web Lock, so one tab drains a scope; the service worker relays `sync`, `periodicsync` and `push` to an open page | SQLite on OPFS in a dedicated worker | under headless Chromium, two tabs share runs without double execution, a reload resumes a run, and a service-worker event drains due work | passes, in BestBuds' `work.spec.ts` |
| Workers | one Cloudflare Workflows instance per run; each attempt is a `step.do` named by its path and attempt, and each wait is a `step.waitForEvent` with a deadline | the instance's step results, mirrored to D1 | under workerd, retries, sleeps, events and an instance restart behave as the suite expects | passes, in reaktor-cloudflare's `CloudflareWorkflowsTest`: a step fails twice and succeeds on its third attempt, a 2 s sleep, a signal, a workerd restart, and no attempt runs twice |
| Server | Quartz with its clustered JDBC job store | Postgres | two server instances share runs, and when one dies mid-step the other resumes the run | passes, in `QuartzPostgresClusterTest`: the second server finishes the killed server's run on attempt 2 |
| Desktop | `LiveScheduler` in the Hangar host | SQLite | a host restart mid-run resumes the run, and the kernel's device commands run on it | the restart passes in `KernelWorkTest` and on the live Hangar host, where run `live-1` started in host process 66635 and finished in process 68205 after a restart; `work.start` and `work.inspect` reach the host, and in `KernelWorkTest` a cancel reaches the host only through a kernel plan that an operator approved, then the run ends `Cancelled` |

Everywhere except Workers, the store owns the run and a host only wakes the executor. On Workers the Workflows instance owns the run. `Replay` there is deterministic because it only reads recorded results: each attempt, each wait and each reading of the clock is a recorded step, which is what Cloudflare Workflows requires of its code. A wait ends at its due time or after 30 days, whichever is sooner, and then waits again. The Workers host refuses workflows with child runs, and checkpoints are not durable there. A definition check against Cloudflare's step and size limits is not built.

The Android reboot proof ran on the evening of 6 November. The run was stored waiting on a 3-minute timer, the phone was rebooted, and the timer fell due while the phone waited for its PIN. After the unlock, the app started in the background, not on screen, and finished the run in a new launch about 20 seconds later. Desktop control is built: the kernel attaches the desktop host's `WorkController` as a command host, the Cloud pane asks the kernel for a `work-control` plan for whichever host it shows, and the command runs only after an operator approves that plan.

### First measurements

These come from debug builds and are not benchmarks. Throughput is the conformance suite's hundred-run test: 100 runs of a three-step diamond, 300 steps, on an in-memory store, drained until idle.

| Host | 300 steps took | Steps per second |
| --- | --- | --- |
| JVM, an M4 Pro Mac | 291 ms | about 1,030 |
| Kotlin/JS, `node:sqlite` | 476 ms | about 630 |
| Android, SM-S918B | 2,725 ms | about 110 |
| iOS, iPhone 14 Pro | 963 ms | about 310 |

Wake latency is the time from a timer falling due to its next step starting.

| Host | Latency |
| --- | --- |
| Desktop, `LiveScheduler` | 3 to 12 ms over three runs |
| Server, Quartz with an in-memory job store | 5 to 6 ms over two runs |
| iOS, app active | 11 ms |
| Browser, two tabs sharing a run | 16 ms |
| Browser, after a reload | 17 ms |
| Workers, under workerd | 69 ms |
| iOS, relaunch after a kill | about 1 s from launch |
| Android, relaunch after a force-stop | about 2.5 s from launch |
| iOS, killed, then launched by iOS in the background | 5 min |

Not measured yet: throughput on Workers, on the Postgres store and in a real browser on OPFS, and wake latency with Quartz's JDBC job store.

## 5. The engine

- **Definition (the graph).** A workflow is a `Workflow` node whose child graph holds its steps. The workflow's inputs and outputs are the child graph's own ports.
- **Planner (the traversal).** `topological(Upstream)` orders the steps and rejects a cycle when the workflow is built. Readiness is a pure function of the journal. `breadthFirst(Downstream)` finds what a failure or a cancellation affects.
- **Executor (reaktor-work).** The store holds runs with claims, fences and leases, and the journal holds each run's events. The executor runs ready steps in bounded attempts and records each result once.
- **Hosts.** A host wakes the executor and maps timers, waits and retries onto what its platform offers.

Temporal replays workflow code against the event history, so workflow code must be deterministic, and every change to it needs a version marker. Cloudflare Workflows records each `step.do`, but its control flow is plain code that runs again from the top. Here the control flow lives in the graph: which steps exist, what each consumes and the conditions on its inputs. Resuming a run therefore means reading the journal and computing the ready set, and no workflow code runs again. That removes Temporal's determinism rules, and it lets the Graph pane draw a run, because the run's structure is data.

## 6. Every workflow system in reaktor+

| # | System | Where | Model | Durability | Use today | Fate |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Graph-runtime `Workflow`, `Stage`, `ActivityNode`, `PersistentActorNode` | reaktor-graph-runtime, BestBuds Workers | steps are graph nodes; they run inside the request through reaktor-service `activity` | none; the flow's own D1 or Postgres writes | 68 Workflow nodes, 26 flows, 189 step cards | becomes the definition surface |
| 2 | Worker crons | `BestBudsWorker.scheduled`; Manna api `*/5`, Manna web `23 * * * *` | a cron event calls an internal route | the route's own writes | engagement runs, account purge, backups, circle formation | start runs |
| 3 | reaktor-work | reaktor-work | store, runtime, schedulers, durable graph, request DAG, journal | SQLite, D1, text ObjectDatabase; fences, leases, receipts | 2 BestBuds definitions, kernel device commands, devtools | becomes the engine |
| 4 | Manna documentation export (TypeScript) | manna api | one Cloudflare Workflows step that renders and stores the reading edition | Cloudflare Workflows | live | later |
| 5 | Manna `ConnectorQueue` (TypeScript) | manna api Durable Object | SQLite queue with attempts and `not_before`, drained by an alarm | Durable Object SQLite | live | later |
| 6 | Conductor protocols (Ask, All, Council, Pipeline, Planned) | reaktor-conductor | `Conductor` runs a protocol over a thread | thread JSON | live | become templates |
| 7 | Conductor `WorkflowDefinition` with `ProtocolCheckpoint`; `WorkflowRunbooks` build-review-repair | reaktor-conductor | Agent, Check and Gate stages joined by conditional edges, with a repair loop; 64 stages at most | checkpoint | engine pane | moves onto the engine |
| 8 | Task graphs and `TaskGraphRuntime` | reaktor-conductor workspace | a coordinator and one actor per task on reaktor-graph; dependencies are `TaskOutcomes` ports; repair loops, limits, token budgets | board store | runtime built; graphs G1-G38 archived on 28 Oct | moves onto the engine; its coordinator seeds the planner |
| 9 | `WorkPlan` and `PlanGraph`, with seats, leases, claims and schedules | reaktor-conductor | a checklist per thread with parts, dependencies and relations | owner JSON files with revisions | 53 plans in the BestBuds owner on 3 Nov | stays the human checklist (decision 1) |
| 10 | Agent board (`BoardTask`) | reaktor-conductor | backlog, planning, review and done | board store | 41 files; task graph members are stored as BoardTasks | folds into plans and the engine |
| 11 | Hybrid seat (ChatGPT and Gemini) | reaktor-conductor | a Planning, Executing and Reviewing loop with handoffs | handoff records | third agent seat | becomes a `repeat` workflow |
| 12 | Thread causal event DAG | reaktor-conductor | conversation events that point at earlier events | JSON | live | stays separate; it is a log |
| 13 | `HangarWorkflow` prompts | conductor CLI | MCP prompt templates for plan work | none | live | stays; prompts |
| 14 | Kernel operations (`OperationalControlPlane`) | bestbuds kernel | request, plan, approval, execution; `RunEvent` journal | SQLite journal, event-sourced per run | every kernel operation | moves onto the engine; its journal is the model for engine events |
| 15 | Deploy kernels (21 Worker deploys, server rollout) | bestbuds kernel | one step each, with receipts and rollback | receipts | live | steps of the release workflow |
| 16 | Deploy lifecycle | engine `DeployLifecycle.kt` | 63 steps in 10 phases, a hardcoded catalogue; states derived from receipts | none; derived | Deploy tab | becomes a workflow definition |
| 17 | Cluster tasks (`ClusterTaskWorkflow`) | reaktor-cluster, kernel | stages and edges with conditions for build-cluster jobs | kernel state | live | moves onto the engine |
| 18 | Build plane | dependeasy, remote-dev, Gradle | Gradle's task DAG, routed by remote-dev | Gradle | live | stays separate |
| 19 | Hangar flows | bestbuds | scripted UI test flows | none | live | stays separate |

reaktor-work becomes the engine, and 11 systems move onto it: rows 1, 2, 6, 7, 8, 10, 11, 14, 15, 16 and 17. The two Manna systems follow later. Five stay separate: plans, the conversation log, prompt templates, the Gradle build and test flows.

Three patterns repeat, and the engine makes each of them first-class:

- Four systems are a DAG with conditions on the edges: the conductor workflow (Succeeded, Failed, Pass, Repair), task graphs (Always, Succeeded, Failed, Pass, Repair, Accepted), cluster tasks and the deploy lifecycle.
- Three have human gates: the conductor workflow, kernel approvals and deploy steps.
- Three have their own repair or retry loops: task graphs, the Hybrid seat and the connector queue.

## 7. What was built: one traversal

The API lives in `reaktor-graph-port`, package `portgraph.traversal`:

- `Relation` gives an element's neighbours for one kind of link.
- `Step.Enter` and `Step.Leave` carry the element, its parent and its depth.
- `depthFirst(relation, enter)` yields every element once, as an Enter step and a Leave step. When `enter` returns false, the element is still yielded, and its children are skipped.
- `breadthFirst(relation, enter)` yields Enter steps only, in level order.
- `topological(after)` orders a collection so each element comes after everything `after` yields for it. Ties keep the collection's order, and a cycle fails with a message naming each pair, such as "'build' waits on 'review'".
- Relations: `Members` (what a graph or node holds), `Contains` (Members, plus every port, plus each consumer port's edge), `Upstream` (the owners of the providers a thing consumes), `Downstream` (the owners of the consumers of what a thing provides), and `Exits` in the runtime (navigation).

| Element | `members()` |
| --- | --- |
| `PortGraph` | its nodes, in declaration order |
| `Graph` | its nodes, then the owned graphs that no member node holds |
| `Workflow` | its child graph |
| `ContainerNode` | its route when the route is not attached to the graph, then its graphs |
| any other node | nothing |

`PortGraph.nodes` and `Graph.owned` now keep declaration order through an atomic ordered map. They used reaktor-core's `ConcurrentHashMap`, whose order followed random UUIDs. `shape()`, `watched()`, `tree()`, `children()`, route coverage, telemetry instrumentation and Studio's port witness all use the traversal. Both old visitor files, `TelemetryPlanVisitor`, Studio's `PortCollector` and `GraphOwner` are deleted. Devtools' `instrumentGraph` and `GraphJson` still walk on their own, because moving them would change what they cover.

```kotlin
val ports = graph.depthFirst(Contains).mapNotNull { (it as? Step.Enter)?.element as? Port<*> }
val order = workflow.childGraph.nodes.topological()
val reached = root.breadthFirst(Exits).mapTo(HashSet()) { it.element }
```

Verification. The "Reaktor graph" Codex lane wrote 10 unit tests and compared the old walks with the new ones on real graphs. I reviewed every change and reran the decisive checks.

- The BestBuds app graph (13 scopes, 163 nodes, 368 wires, 53 routes), Hangar's workbench graph (2 scopes, 137 nodes, 438 wires, 156 routes) and Studio's app graph agree with the old code on every scope, node, wire and route, in the same order. They also agree on the definition digest, `tree()`, `children()` and coverage. Studio's storyboard (38 screens, 13 features, 53 links) is identical.
- Telemetry now also instruments a graph's own ports and the routes that containers hold: 14 more ports in BestBuds and 4 in Hangar.
- The review found two order regressions, and both are fixed. `shape()` emitted a graph's own nodes after its nested scopes, and nodes with equal labels came out in a different order. A graph's content now comes first, and ties follow declaration order.
- Cost, as the median of 20 warm runs, new against old: `shape()` 0.84x to 1.0x and `wire()` 0.61x to 0.68x. `tree()` and `children()` vary between 0.8x and 2.6x on walks that take under 0.02 ms.
- Suites pass with no failures: graph-port 40, graph-runtime 54, telemetry 66, reaktor-graph 39 and the conductor's task graph tests 23. The iOS compiles and the app's JS compile pass.
- Failures outside this change: Studio's navigation test sees capture move 23 of 40 screen cards, although its storyboard is identical under both walks, so the traversal is not what moves them. I have not run that test on the code from before this week's graph changes. DesktopGraphCoverageTest's Cloud frame and two DatabaseStudioContractsTest checks failed before this change.
- The two old-against-new tests carry a copy of the old algorithm, so they stay outside the tree in `~/dev/tmp/reaktor-graph-lane/evidence/`.

## 8. Against Temporal

| Capability | Temporal | Cloudflare Workflows | This engine |
| --- | --- | --- | --- |
| Where it runs | a Temporal cluster plus workers on servers | Cloudflare Workers | Android, iOS, browsers, Workers, JVM servers and the desktop |
| Definition | deterministic code | code with recorded steps | a typed graph, checked when it is built |
| Resume | replays code against history | reruns code and returns recorded step results | reads the journal and computes the ready set |
| Change a definition | version markers or worker versioning | redeploy; running instances continue on the old code | the definition digest pins each run, and a changed definition quarantines its open runs; migrations are not built |
| Visibility | event history and a search UI | an instance status API and dashboard | the inspector pages runs, definitions and the journal today; the Graph pane draws the run on its definition in Phase 2 |
| Human gates | signals and updates written by hand | `waitForEvent` | signals with a sender requirement and a timeout |
| Effects and idempotency | left to the activity's author | left to the step's author | each step declares an effect, and the engine applies the matching retry rule |
| Scale and maturity | proven at very large scale | managed by Cloudflare | first measurements only: about 110 to 1,030 steps per second on debug builds, and wakes within 3 to 69 ms (section 4) |

Before it can claim parity, the engine must also do what Temporal does with timers at scale, signals, queries and updates, child workflows, continue-as-new for long histories, task queues and worker routing, activity heartbeats, cancellation scopes, schedules and a search index over runs. Phase 1 built timers, signals, child workflows, heartbeats through checkpoints that renew a step's lease, and cancellation with undo, and the conformance suite proves each of them. Schedules are not built; they come with the Worker crons in Phase 3. Queries and updates, continue-as-new, worker routing and a search index over runs come when a migrated system needs them. The claim to beat Temporal waits for the benchmarks.

## 9. Decisions

1. **Plans and runs** (decided and confirmed 6 Nov): agent work plans, the `WorkPlan` and `PlanGraph` that every Claude, Codex and Hangar thread writes with `plan.write`, stay the human checklist. The engine never executes them, and an item can point at a run and mirror its state. The deploy DAG and Manna's missions are separate: the deploy lifecycle becomes a release workflow in Phase 3, and Manna's missions are unchanged.
2. **Web** (decided 6 Nov): the browser is a target, with pages, dedicated workers and service workers as hosts.
