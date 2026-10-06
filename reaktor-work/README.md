# reaktor-work

**Experimental. Implementation status updated 6 October 2026.**

The selected direction is to remove Meeseeks and make Work a graph-owned durable execution substrate with three separate responsibilities:

- **WorkStore:** authoritative intent, atomic claims, checkpoints and result receipts using Reaktor data contracts.
- **WorkRuntime:** bounded execution attempts using coroutines and a minimal authorized headless graph scope.
- **WorkScheduler:** direct platform wakeups and reconciliation. A wakeup is an execution opportunity, not the owner of work.

| Host | Selected backend |
| --- | --- |
| Android | WorkManager, with Application-owned worker construction and native transfer policy |
| iOS | BGTaskScheduler; background URLSession integration in media/IO when required |
| Browser | Window/dedicated-worker execution and a separate service-worker entrypoint; feature-detected sync/push wakeups, Web Locks and persistent fenced claims |
| Cloudflare | Workflows for durable steps/waits; Queues for buffered delivery; authenticated GCP Pub/Sub through Reaktor Google/Bus |
| Spring server | Spring-managed Quartz with qualified JDBC persistence/clustering; queue and GCP Pub/Sub delivery adapters |

**Implementation started, 5 October 2026.** Meeseeks and the ten placeholder workers have been removed. `Feature.Work` now holds a headless graph `WorkRuntime`. `ObjectWorkStore` uses text-backed ObjectDatabase conditional writes for scoped admission, monotonic fences, leases, checkpoints, cancellation and provider handoffs. Typed request DAGs and bounded static durable graphs use existing provider ports. JVM conformance checks cover competing file-backed SQLite connections and database reopen; Work dependency checks enforce the headless boundary. Direct Android/iOS/browser/Cloudflare/Quartz bindings compile, but device/provider qualification and real product-effect migrations remain open.

Browser storage now uses the DB module's SQLite/Wasm/OPFS worker profile. Service-worker access to that authority needs separate qualification; IndexedDB must not become a hidden competing journal. Durable acceptance cannot promise a mobile OS or closed browser grants execution. Repeated attempts require resource-side identity/reconciliation, and cloud placement requires an explicit authorized handoff.

The [public Work architecture](https://reaktor.build/docs/reaktor-work) owns the framework model and platform profiles. Its [feature comparison](https://reaktor.build/docs/reaktor-work#feature-comparison) assesses the Reaktor design against Twitter Nodes, Temporal, Restate, Conductor OSS and Ray. These are comparison references only; no adapters, dependencies or deployments are planned. Coroutines and Cloudflare Workflows remain included execution backends. Spring uses [Reaktor's own durable graph coordinator](https://reaktor.build/docs/reaktor-work#spring-orchestration) over its authoritative journal, with Quartz and brokers supplying timing and delivery.

The [DAG contract](https://reaktor.build/docs/reaktor-work#dag) separates request-scoped evaluation from durable runs; the [journal protocol](https://reaktor.build/docs/reaktor-work#logs) defines retained transitions and replayable projections. No numerical performance benchmark or parity claim exists yet; the published measurement protocol defines the required evidence.

SQLite and D1 authorities can now opt into retained changes with `journalStorePrefix = WorkScope.STORE_PREFIX`. Record changes and journal entries commit atomically. `readEvents(scope, consumerId, limit)` returns ordered, bounded batches; `acknowledgeEvents(batch)` advances a durable conditional cursor after processing. Lost acknowledgements redeliver the same sequence identities. Existing rows are not backfilled, no automatic journal retention policy exists, and external projections/effects still require idempotency. Due-work discovery still scans a scope.

13 JVM Work conformance checks and two native D1 workerd/Miniflare checks pass, including journal rollback, cursor recovery and JavaScript provider errors. An Android diagnostic executes through WorkManager and retains its receipt across force-stop/relaunch. iOS arm64 application builds pass; actual BGTaskScheduler execution and the complete device/provider matrix remain pending. The static durable graph is limited to 64 nodes; waits/signals, dynamic expansion, compensation, evolution controls and real domain migrations remain open.

Cloudflare and Secrets now use Auth Core's pure bearer-header helper; Cloudflare has a checked headless dependency boundary and no test-only UI asset workaround. Existing Auth APIs remain compatible, and all 107 Auth JVM checks pass. Google/Pub/Sub's broader host dependencies remain a qualification gate.

The authenticated [delivery and removal roadmap](https://reaktor.build/docs/private/reaktor-work-roadmap) owns caller/payload/store migration, milestones and acceptance gates. The dated progress notes distinguish implemented contracts from remaining design and qualification work. The previous upgrade recommendation is retired; dependency and caller removal follow the cutover plan rather than an unsupported deletion that breaks consumers.
