# reaktor-work

reaktor-work runs durable workflows on Android, iOS, browsers, Cloudflare Workers, JVM servers and the desktop. A workflow is a graph node, and its steps are graph nodes wired by their inputs. The engine records each step's result once, so a run outlives the process that started it. After a crash, a force-stop, a reload or a lost server, the run continues from its last recorded step.

Control flow lives in the graph. Resuming a run means reading its recorded results and working out which steps are ready. No workflow code runs twice, so there are no determinism rules to follow.

**Status, 6 November 2026.** Phase 1 of [the workflow engine plan](../plans/workflow-engine.md) is built. The conformance suite passes on the JVM, in Kotlin/JS, on an Android phone and on an iPhone. Each host has passed its platform proof, a reboot of the Android phone included.

## A workflow

```kotlin
class Chores(graph: Graph) : Workflow(graph, "chores", "Chores"), Durable<String, String> {
    override val input by input<String>()
    val fetch by step(input, effect = Effect.ReadOnly) { name -> "list for $name" }
    val rest by timer(2.seconds)
    val approval by signal<String>()
    val finish by step(fetch, rest, approval, effect = Effect.ReadOnly) { list, _, by -> "$list, approved by $by" }
    override val output = finish
}
```

- Each property names a step, and that name keys the step's recorded result.
- A step's inputs are earlier steps. The step registers one consumer port per input, so the graph itself holds the dependencies.
- A step takes zero to four inputs, and its body receives their recorded values.
- `effect` is the step's reaktor-service `Effect`, and it sets what happens when an attempt dies (see below).

Running it:

```kotlin
val chores = graph.Node { Chores(it) }
val work = graph.Node { Work(it, scope, ObjectWorkStore(database), scheduler, authority, owner = "app") }

val run = work.start(chores, "ada", key = "chores-1")
run.signal(chores.approval, "bea")
val list: String? = run.result()
```

- `Work` is the graph node that owns one scope's runs. It finds the workflows in its graph, so a workflow is installed by being a node there.
- The key names the run. Starting the same key with the same input returns the same run, and a different input fails with `RunConflict`.
- `start` stores the run and arms the host's next wake.
- `execute` runs the run inside the caller, which suits a request handler. While the run only reads, it stays in memory and is stored once, when it ends or starts to wait. Before it runs a step with any other effect, or a child run, it is stored, and from then on each change is written as usual. Until it is stored, `get`, inspection and signals do not see it.
- `Run` offers `updates` (a flow of records), `record()`, `result()`, `signal()` and `cancel()`.
- `drain()` runs whatever is due and returns how many runs and steps moved. A host calls it when it wakes.

## Steps and effects

When an attempt dies without an answer (a crash, a timeout or an exception), the step's effect decides what happens next:

- `ReadOnly` and `LiveRead` steps run again after 1 s, then 2 s, for at most three attempts.
- A step with `reconcile` runs that function first on its next attempt, to find out whether the lost attempt took effect.
- Any other step stops in `Unknown`, and its run is `Blocked` until an operator retries or reconciles it.

The rest of a step's contract:

- `fail(reason)` fails the step for good. A condition such as `charge.failed` can route the run around it.
- `effectKey` is the same for every attempt of a step in a run. Use it as the idempotency key of an external write.
- `checkpoint(value)` saves progress inside a long step, and the next attempt reads it as `checkpoint`.
- `undo` reverses a finished step. When a run is cancelled, or fails with no step handling the failure, finished steps are undone in reverse order.
- A step times out after `timeout`, which is 2 minutes unless set. Inputs, results and signals are limited to 64 KB each.

## Waits, branches and child runs

- `signal<T>()` waits for a value sent with `run.signal(...)`. `sender` limits who may send it, which makes it a gate, and `timeout` ends the wait as `TimedOut`.
- `timer(after)` waits for a duration, counted from when its condition holds.
- `on = condition` decides whether a step runs. Conditions read recorded outcomes: `x.succeeded`, `x.failed`, `x.skipped`, `x.timedOut` and `x.matches("why") { value -> ... }`, combined with `and`, `or` and `!`. A step whose condition can no longer hold is skipped.
- `either(a, b)` takes the option that ran.
- `call(child, input)` runs a child workflow, and `each(child, items)` runs one child per item.
- `loop(child, seed, limit) { result -> next }` reruns a child until `next` returns null or the limit is reached.

Each run pins the digest of its workflow's definition. When the definition changes, its open runs are quarantined, so no run continues under a shape it did not start with.

## Hosts

| Target | Host | Wakes the engine with | Store |
| --- | --- | --- | --- |
| Android | `AndroidWorkScheduler`, `ReaktorDrainWorker` | WorkManager unique one-time work at the next wake | SQLite |
| iOS | `DarwinWorkScheduler` | `LiveScheduler` while the app is active, and a `BGProcessingTask` in the background | SQLite |
| Browser | `BrowserWorkScheduler`, `reaktor-work-service-worker.js` | a page timer, with a Web Lock so one tab drains a scope; the service worker relays `sync`, `periodicsync` and `push` to an open page | SQLite on OPFS in a dedicated worker |
| Workers | `CloudflareWorkflowHost` | one Cloudflare Workflows instance per run; each attempt is a `step.do` and each wait a `step.waitForEvent` with a deadline | the instance's step results, mirrored to D1 |
| Server | `QuartzWorkScheduler`, `ReaktorQuartzDrainJob` | one Quartz trigger per scope; Quartz's clustered JDBC store hands it to a live server | Postgres |
| Desktop | `LiveScheduler` | a coroutine timer in the Hangar host | SQLite |

Everywhere except Workers, the store owns the run, and a host only wakes the engine. On Workers the instance owns the run. `Replay` drives it from the instance's recorded steps, and D1 keeps a copy for queries. A Workers run cannot have child runs or durable checkpoints yet.

An app supplies a `WorkHost`, which opens the `Work` node for a scope when a platform wake arrives. On Android the `Application` implements `AndroidWorkApplication`. On iOS the app calls `register()` while it launches and lists the task identifier in `BGTaskSchedulerPermittedIdentifiers`.

## Inspection and control

`WorkInspector` pages a host's runs, definitions and journal for tools such as devtools, Hangar's Cloud pane and the kernel's `work.inspect`. It returns metadata only, never inputs, results or checkpoints. `WorkController` applies an operator's cancel, retry or reconcile to one run at an exact revision, so a command meant for an older state of the run is refused.

## Packages

```text
dev.shibasis.reaktor.work          Work, Run, Feature.Work
├── run                            RunRecord, RunTransition, StepRecord, RunState, StepState, WorkScope
├── definition                     Durable, input, step, signal, timer, call, each, loop, either, Condition, WorkflowPlan
├── store                          WorkStore, ObjectWorkStore
├── execution                      the planner, the executor, attempts, outcomes, request mode, Replay, WorkScheduler
├── host                           WorkHost, LiveScheduler
├── inspection                     WorkInspector, WorkController, WorkInspectionSession
└── host.android, host.ios, host.browser, host.cloudflare, host.quartz
```

`run` uses no other package. `definition` and `store` use `run`, `execution` uses those three, and `Work` in the root package uses all four. `host` and `inspection` build on `Work`. The main code is 2,570 non-blank lines: 2,159 shared and 411 in the five platform hosts. The tests are 1,076 lines.

`WorkStore` has eleven operations, and every store implements all of them: `admit`, `get`, `replace`, `due`, `nextWake`, `page`, `observe`, `changes`, `history`, `events` and `acknowledge`. `ObjectWorkStore` implements them over any text `ObjectDatabase` with conditional writes, which covers SQLite on every platform, D1 and Postgres. Pass `journalStorePrefix = ObjectWorkStore.JOURNAL_PREFIX` to the database, so each change to a run and its journal entry commit together. Due work comes from a projection of the journal that keeps one wake per open run, so a drain does not read finished runs.

Each journal event names what changed. `RunTransition.State(previous, current)` records a move of the run's state, and `RunTransition.Step(path, previous, current, attempt)` a move of a step's state or attempt. They map onto the kernel's `state` and `progress` run events. A consumer reads with `events(scope, consumer)` and moves its cursor with `acknowledge`. A change leaves the journal once every consumer's cursor has passed it and it is older than the store's `retention`, which is 7 days unless set; `Duration.INFINITE` keeps every change.

## Tests and proofs

The conformance suite in `commonTest` is 23 tests. It covers admission, joins, conditions and `either`, handled and unhandled failure, cancellation with undo, signals, retries with checkpoints, ambiguous and interrupted effects, gates, timers, child runs, stale attempts, due work, journal transitions and retention, request mode, quarantine, denied authority, inspection, plan digests and a hundred concurrent runs. `ReplayConformance` adds 9 tests of the Workers interpreter.

| Target | Store under test | Result |
| --- | --- | --- |
| JVM | SQLite over JDBC | 32 of 32 |
| Kotlin/JS | the browser's SQLite worker protocol, answered by `node:sqlite` | 32 of 32 |
| Android, Samsung SM-S918B, Android 16 | `AndroidSqliteDriver` | 32 of 32 |
| iOS, iPhone 14 Pro | `NativeSqliteDriver` | 32 of 32, in 1.5 s |

Journal pruning also runs against D1 under workerd in reaktor-cloudflare's `D1WorkStoreTest`, and against Postgres in `QuartzPostgresClusterTest`.

Each host also has a platform proof:

| Target | Proof | Result |
| --- | --- | --- |
| Android | a run with a timer survives a force-stop and a reboot | both pass: after a force-stop the run finishes about 2.5 s after the app relaunches; after a reboot, once the phone is unlocked, the app finishes it in a background launch about 20 s later |
| iOS | a run resumes after the app is killed, and in a background launch | after a kill, the run finishes about 1 s after relaunch; in a second proof the app was killed while its run waited on a timer, iOS launched the app in the background, as the app recorded at launch, and the run finished in that launch 5 min after the timer fell due |
| Browser | under headless Chromium, two tabs share a run without running a step twice, a reload resumes a run, and a service-worker `sync` event drains due work | passes |
| Workers | under workerd, a step fails twice and succeeds on its third attempt, then the run sleeps, waits for a signal and survives a restart of workerd, and no attempt runs twice | passes, in reaktor-cloudflare's `CloudflareWorkflowsTest` |
| Server | two Quartz servers share a Postgres job store, and when one dies mid-step the other finishes the run | passes, in `QuartzPostgresClusterTest` |
| Desktop | a restart of the Hangar host mid-run resumes the run, and the kernel's work commands reach the host | the restart passes in BestBuds' `KernelWorkTest` and on the live Hangar host, where run `live-1` started in one host process and finished in the next; `work.start` and `work.inspect` reach the host, and in `KernelWorkTest` a cancel reaches it only through a kernel plan that an operator approved |

`QuartzPostgresClusterTest` runs when `REAKTOR_WORK_TEST_JDBC_URL` (or the Gradle property `reaktorWorkTestJdbcUrl`) names a disposable Postgres database, and is skipped otherwise.

## Measurements

These are first measurements on debug builds, not benchmarks. Throughput is the hundred-run conformance test: 100 runs of a three-step diamond, 300 steps, drained until idle on an in-memory store.

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

## Open

- **Unmeasured.** Throughput on Workers, on the Postgres store and in a real browser on OPFS, and wake latency with Quartz's JDBC store.
- **Journal consumers.** A consumer that stops reading holds the journal from its cursor on, and no call removes a consumer yet.
- **Changed definitions.** Their runs are quarantined, and no migration maps old step paths to new ones.
- **The service worker** cannot drain on its own. It wakes an open page, and nothing drains while no page is open.
