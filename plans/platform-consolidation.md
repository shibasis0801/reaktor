# Platform consolidation: cloud APIs, build router, Node sidecar, fastlane

Owner request, 6 November 2026:

1. remove every trace of pulumi
2. integrate and consolidate every API from the cloud platforms we use (cloudflare, supabase, gcp, kubernetes, postgres, memgraph, clickhouse, docker, etc) into reaktor-tooling and reaktor-cloud
3. migrate the python build router into the reaktor kernel
4. reaktor kernel's node sidecar process can integrate wrangler and other tools into it and it must use reaktor-graph too
5. we need to keep fastlane, but we need bidi integration (fastlane <=> reaktor (kernel, dependeasy))
6. the code needs to have a proper package structure, proper hierarchy and be human readable

**Diagnosis.** Reaktor+ reaches its platforms through about twenty hand-written clients spread over three reaktor-tooling packages (`cloud`, `infra`, `api`). Every build goes through about 4,300 lines of Python. Node tools start as one-off processes, and fastlane reports nothing back. The fix has four parts: one provider layer in reaktor-cloud, the router inside the kernel, one Node sidecar built on reaktor-graph, and a fastlane contract that works in both directions. Packages are named for what they hold.

## Status at a glance

| Domain | State |
|---|---|
| Pulumi removed | Done 6 Nov: code, build wiring, docs, papers and plans, both Macs, both agents' memories. The provider-list entry in `bestbuds/.reaktor/mcp-providers.json` is removed together with the owner's `workspace trust`. |
| Dagger removed | Done 6 Nov. |
| 1. Target shape | Planned |
| 2. Cloud provider layer | **Step A done 6 Nov 14:41**, by Claude after Codex's part 1. One scripted move put 93 files into one package per platform and rewrote 313 files in reaktor and bestbuds. The packages are `cloud.http`, `cloud.api`, `cloud.inventory`, `cloud.database`, `cloud.cloudflare`, `cloud.google`, `cloud.kubernetes`, `cloud.postgres`, `cloud.memgraph`, `cloud.clickhouse`, `cloud.firebase` and `cloud.wrangler`. reaktor-cloud now sits below reaktor-tooling and builds every target. Tooling keeps `tooling.operation` (the operation vocabulary, NativeExecution and CloudApiTools) and the presentation helpers in `tooling.database`. `tooling.cloud`, `tooling.infra`, `tooling.api` and `tooling.kubernetes` are gone. Each client takes a small request interface from its own package, and the operation types implement it, so no translation layer was needed. Serialized forms are byte-identical: 24 samples across every sealed family, kept by `@SerialName`. Checks passed: `:reaktor-cloud:build` on every target; tests for cloud (78 JVM, 43 JS), tooling (199, 9), service (73), conductor (736), and the kernel and engine Cloud suites (25, 11); companyOS builds, and its Worker now imports `DurableObjectSqlInspector` from `reaktor-reaktor-cloud`; all three build models export. Live on the host since 14:40: `cloud.api.read` on Cloudflare and Supabase. Not done yet: Cloud pane counts side by side (no window has read Cloud), live reads on Google, Kubernetes, Postgres, ClickHouse and Memgraph, and the rest of section 2 (one transport, writes as operations, Docker, Grafana, Kubernetes apply, Supabase). Uncommitted. The move tools, before-copies and a revert are in `~/other/platform-consolidation/step-a/claude`. **Step B, 6 Nov 15:40, by Claude.** One HTTP transport, `cloud.http.Transport`: it retries 429 always, retries 5xx only for reads or calls marked repeatable, honours `Retry-After` up to 10 s, and stops after three attempts; the bounded client follows the same rule. With it the live inventory reads Cloudflare again: 246 resources and 494 relations, where one passing 503 had left 61 and 59. New clients: `cloud.supabase` reads each project as its Postgres, with service health; the 2 projects merge into the 2 Postgres resources Cloudflare saw through Hyperdrive, because a platform's own API now wins a merge. `cloud.docker` speaks the Engine API over the local socket or `ssh … docker system dial-stdio` and takes its contexts from the Docker CLI; live on m1-worker it reads engine 28.3.0, 9 containers, 2 compose projects and the published ports. `cloud.grafana` reads dashboards, folders, data sources and alert rules and wakes the sleeping appliance first; live it reads Grafana 13.2.4 with 16 dashboards, 1 folder and 5 data sources (alerting is off in this Grafana by design). `KubernetesApply` does server-side apply and plans with a server-side dry run; live, both observability backends plan with 0 changes. `cloud.api` also reaches Docker (107 operations from the Engine v1.51 spec), Grafana (314) and the cluster's own `/openapi/v3` (845), and all three read live. Writes are operations: `cloud.api.plan` returns the exact request, the live resource, the changed fields and a digest; `cloud.api.write` and `cloud.api.delete` refuse a request that differs from its plan; receipts name the remote version; Hangar's cloud writes carry the digest in their approval fingerprint. Credentials are named, never copied: the workspace's `.reaktor/cloud.json` names the Grafana site, the kubeconfig and the Docker context, and `cloud.http.Credentials` reads each value from the tracked config. Checks: cloud 117 JVM and 43 JS tests, tooling 189 and 9, kernel 25, engine 4. Hangar was installed and the host upgraded at 15:38. Through the kernel, `cloud.api.read` answers live on Cloudflare, Google, Supabase, Kubernetes, Docker and Grafana. One dev write per platform went through plan, digest and receipt, each creating a uniquely named resource and deleting it again: a Docker image pull on m1-worker, a Grafana folder, a Kubernetes ConfigMap, a Cloudflare KV namespace, a Pub/Sub topic and a Supabase secret on BestBuds Dev. Nothing was left behind. The CLI seat's own policy approved those writes, so for that seat the approval step is the policy; Hangar's approvals now bind to the plan digest. Cloudflare answered one create with a passing 401 and the same planned call then succeeded. Receipts name the remote version (Kubernetes resourceVersion, Grafana version, a pull's image digest) or, for unversioned resources, the created id; the digest and id naming goes live with the next install. Still open: SQL and Cypher reads through the kernel. ClickHouse reads need Hangar's selected environment to be dev or production (it is local), and Postgres and Memgraph have no kernel read command yet, only Hangar's Data pane. Uncommitted; before-copies are in `~/other/platform-consolidation/step-b/before`. |
| 3. Build router in the kernel | **Step C started 6 Nov, by Claude.** Job control and the phone inventory no longer run Python. `cluster.build` in reaktor-cluster now holds `BuildControl`, `RouterState`, `JobSites` (this Mac, or the worker through one ssh shell command per step) and `ControlFiles`; `ClusterControls` calls them in process. They read the same receipts and job directories, and the router's supervisor (`build_control.py _supervise`) still runs each build. `ClusterDeviceReader` probes m1-worker's adb and devicectl over ssh itself, with the same 8 s probe limit and 24 KiB capture limit. Checks: reaktor-cluster 137 tests, among them 9 for job control against the real supervisor and a zsh stand-in for the worker, and the kernel's cluster test. Live since 16:05 (host 21051): Kotlin inspect matches Python field for field on a local and an m1-worker build, and a routed build was cancelled through `cluster.job.stop` (exit 130, receipt cancelled). That stop showed a race: the supervisor can write Cancelled and exit between a state read and the liveness check, so the stop reported failure although it worked. Python has the same race. The fix re-reads the state once after the supervisor exits and has its own test. The fix and the ssh phone probe went live at 17:04 (host 65137): a second routed build was cancelled through `cluster.job.stop` and reported Cancelled (exit 130, receipt cancelled), and `cluster.devices` matches the Python inventory (adb 0 rows; devicectl 1 iPhone record, disconnected). Next: Docker port forwarding into `cloud.docker`, then local routing, worker routing, publication and resume, and the `./gradlew` shim. |
| 4. Node sidecar | **Step D started 6 Nov, by Claude.** `kernelSidecar` (`modules/kernel/sidecar`) is a Kotlin/JS Node program with a JVM client. Common Kotlin holds the contracts (`SidecarHost`, `WranglerTools`), the graph that serves them, and the line framing. jsMain holds only the Node edges: the socket server, child processes, and loading a project's own Wrangler. jvmMain holds `SidecarProcess`, which starts the dependeasy-pinned Node 24.21.0, owns the Unix socket and starts Node again after a loss, and `SidecarClient`, which reaches both contracts through reaktor-service's `SocketLink`. Node's built-in modules come from JetBrains' kotlin-node 2026.8.4, the same wrappers release as the Karakum runtime, so no Node externals are hand-written. Wrangler's programmatic API is bound with Karakum in reaktor-cloud's `cloud.wrangler` (jsMain): 18 declarations generated from `reaktor-cloud/ts/karakum.ts`, covering `readConfig` with its arguments and options, the config with the ten binding kinds the 20 bestbuds Workers use, and Wrangler's package manifest. dependeasy gained `nodeProgram()`: the module's linked Kotlin/JS library, a generated entry that calls its exported `start`, and a manifest the JVM client reads from its classpath. A dependeasy library without the Android target no longer creates Android variants; those had left `:engine` unable to choose a variant of the new module. Checks: 5 sidecar tests against the real Node program (state and graph; Wrangler's own config reader; Wrangler's command line streaming inside the sidecar and exiting 0; a new Node process after the old one is killed), Hangar's island tests (4), and reaktor-cloud's JS build. The kernel's test for the operations passes (since 17:36). Live since 17:04 (host 65137): `sidecar.state` reports Node v24.21.0 running as the host's child, with both contracts in its graph; `sidecar.wrangler.installation` and `sidecar.wrangler.configuration` read `targets/configServer` (Wrangler 4.148.0; 2 R2, 1 service and 1 Hyperdrive binding, the same as a direct read); a directory outside the workspace is refused. Hangar's whole-system view gains a Node sidecar island from `sidecar.graph`; it is installed and goes live with the next host upgrade. Lockfiles: reaktor gains the `reaktor-cloud/ts` importer and bestbuds the two `kernelSidecar` importers; no package versions changed. Wrangler through the sidecar, live since 17:39 (host 18673). dependeasy's Worker tasks (deploy with its dry run and its version and deployment reads, secrets, D1 migrations and queries, rollback, and `logs`) start `~/.reaktor/kernel/sidecar/wrangler` instead of `pnpm exec wrangler`, and are pinned to this Mac. Only the process that holds the host lock publishes that command, when it opens its first workspace. The command forwards its arguments, working directory, environment and standard input over `kernel.sock`, streams Wrangler's output back and exits with Wrangler's code, or exits 2 with "The kernel's sidecar at … did not run Wrangler" when nothing answers. The sidecar's JavaScript ships inside the Hangar bundle (58 modules and an entry, addressed by digest) and is unpacked to `~/.reaktor/kernel/sidecar/programs/<digest>`, so rebuilding the tree no longer changes what the host runs. Checks: 6 sidecar tests (new: the published command prints Wrangler's version, returns Wrangler's exit 1 for an unknown command, and exits 2 once the sidecar is gone), the kernel's sidecar test, and dependeasy's TypedKernelExecutionTest (3), GradlePlanningTest (7) and WebContractsTest (8). Live: `ship.plan` for config-service in dev ran its dry run and remote reads through the published command; the plan records that command and the previous version `cecd8763…`, the one the last receipt recorded. An authenticated `deployments list` for config-service-dev ran as a child of the sidecar (pid 18735) with the project's own Wrangler 4.148.0. The launchd PATH no longer carries Pulumi's folder. Deployed through the sidecar on 7 Nov. The fleet went through Gradle with a request built from the fresh plan files: an all-Workers plan is larger than the CLI's 1 MiB `--args` cap, so `ship.apply` cannot carry it (and `ship.apply` asks for approval only from the generic `cli` seat; `--seat claude-code` is superadmin). Dev, 00:33-00:46: all 16 Workers. config-service-dev went from cecd8763 to 5878a5ad, and its receipt fe61aae2 names cecd8763 as the rollback target, the version the plan recorded. Prod, 01:37-01:47: 19 Workers, each receipt naming the previously live version as its rollback target, plus the social and docs D1 ledgers (nothing pending). Manna, 01:53-01:55: API ae154956, then web eb676f26 (rollback targets c0bf1fc4 and c54404bf; its D1 ledger had nothing pending). Every fresh plan names `~/.reaktor/kernel/sidecar/wrangler`. Held: prod notification (its new watchdog emails alert recipients nobody has chosen, and three secrets are missing); CompanyOS (Wrangler's esbuild follows the generated tsconfig `paths`, which point each Kotlin package at its `.d.mts`, so a runtime import finds no export; live is still the 12 Sep version); the app server. Its image now builds on the worker's Docker through a forwarded socket (the server release honours a unix `DOCKER_HOST`) and is pushed as `bestbuds-app-server@sha256:938ae136…`; the rollout plan is ready (live ba08d85e, schema nothing pending). But the declared manifest reads two `app-server-secrets` keys that prod does not have, `reaktor_auth_device_credential_jwk` and `reaktor_auth_device_credential_next_jwk`: the E2EE device-credential key whose public half the apps pin. The push also needs gcloud on Python 3.12 (`CLOUDSDK_PYTHON`), because Spring Boot merges the credential helper's stderr into its JSON and gcloud warns about Python 3.9, and `--no-daemon`, because a shared daemon started without Homebrew on its PATH cannot find the helper. Found on the way: a host upgrade at 01:28:42 cut the socket while analytics was deploying. Wrangler finished and bbf452ff went live, but the command exited 2 and receipt 3b15b4de says Failed. The command should wait for the socket before it starts, and report an unknown outcome when the link drops mid-run. `bestbuds:clickhouse` keeps no ledger: every apply re-runs its 8 idempotent files and moves `metadata_modification_time`, so a plan made before any apply is stale and fails as "ClickHouse SQL failed; database details withheld". Two limits found: one Worker plan is 1.04 MB as compact JSON, just under the CLI's 1 MiB `--args` cap, because `gradlePlan.tasks` lists 1.02 MB of tasks; and `wrangler types` and the local `wrangler dev` servers (package scripts, Playwright) still start Wrangler directly, because they touch no Cloudflare API and run wherever the build runs, including the worker Mac, which has no sidecar. Uncommitted. |
| 5. fastlane in both directions | Planned |
| 6. Scripts to Kotlin | Planned |
| 7. Readability | Planned; applies to every step |

## 1. Target shape

The layers, from the bottom up. A layer depends only on the layers below it.

```
dependeasy                                  build model and Gradle plugins (included build, no reaktor dependency)
reaktor-core · reaktor-graph-runtime · reaktor-service
reaktor-cloud                               talks to every platform
reaktor-cluster                             machines, leases, storage, build routing
reaktor-tooling                             developer operations over cloud and cluster
kernel  (bestbuds/modules/kernel)           the one host: Hangar, CLI and MCP door read it
  kernelSidecar (modules/kernel/sidecar)    Node tools as graph nodes: common contracts and graph, js host, jvm client
fastlane plugin                             lanes call the kernel and report back
```

- **reaktor-cloud moves under reaktor-tooling.** Today reaktor-cloud depends on reaktor-tooling for the inventory model. The model moves down into reaktor-cloud, so tooling depends on cloud and not the reverse.
- **One package per platform.** Each package has one `Client` for calls, the platform's own resource types, and an `Inventory` reader.
- **No grab-bag packages.** `infra`, `api` and top-level files go. Each file is named after the type it declares.
- **dependeasy stays at the bottom.** It cannot depend on reaktor-cloud, because that would create a cycle. A deploy kernel calls the kernel or the sidecar through its declared command, as it calls `wrangler` today.
- **reaktor-cloud builds every target.** reaktor-tooling builds them all, so the module it depends on must too. Only the common inventory model compiles off the JVM; the clients stay in `jvmMain`.
- **Places move to reaktor-service.** `CloudLocator` and `AirportPlaces` go beside `FleetPlace` and `PlaceSource`, which already live there. The server uses only `CloudLocator`, so it no longer inherits the provider SDKs. reaktor-notification's unused dependency on reaktor-cloud is dropped.
- **Neighbouring modules keep their own concerns.** reaktor-cloud is the operator side: management APIs and admin access.
  - reaktor-cloudflare keeps the Worker runtime: bindings for D1, R2, KV and Durable Objects inside a Worker.
  - reaktor-google keeps what app users touch: Sheets, account connect and the app's Pub/Sub adapter.
  - reaktor-db keeps the app's runtime data layer.

  Where an operator client needs a driver one of these already wraps (reaktor-google's `JvmPubSubAdapter`, reaktor-db's `GraphDb`), it reuses it instead of keeping a parallel copy. Each move checks this first.
- **The sidecar lives in the kernel (owner, 6 Nov).** It is the multiplatform project `kernelSidecar` inside `modules/kernel`, as much of it in common Kotlin as possible, on reaktor-graph-runtime and reaktor-service. Bindings to tools stay in libraries: Wrangler's programmatic API is generated with Karakum into reaktor-cloud's `cloud.wrangler` (jsMain, declarations only, so the Workers that consume reaktor-cloud do not inherit Wrangler).

Target tree for reaktor-cloud (JVM unless marked common):

```
dev.shibasis.reaktor.cloud
  http          Transport, Credentials, Paging, RateLimit, WriteReceipt
  api           Spec, Surface                          every documented endpoint, read from the provider's spec
  inventory     Resource, Relation, Inventory, Audit   common; moved from tooling.cloud
  database      Statement, BoundQuery, Receipt, Plan   common; the contracts shared by every SQL and Cypher client
  cloudflare    Client, Workers, D1, R2, Kv, Queues, DurableObjects, Ai, Inventory
  google        Client, SignIn, Compute, PubSub, ArtifactRegistry, SecretManager, Monitoring, Inventory
  supabase      Client, Projects, Inventory            Management API; SQL goes through postgres
  kubernetes    Client, Workloads, Logs, PortForward, Apply, Inventory
  postgres      Client, Catalog, Migrations, QueryPlans
  memgraph      Client, Pages, Inspection
  clickhouse    Client, Spans, Queries
  docker        Client, Images, Containers, Compose    Engine API, local socket or the worker's
  grafana       Client, Dashboards, DataSources, Folders, AlertRules
  firebase      Client, Crashlytics
  wrangler      Inventory, Verify                      from wrangler.json, no credentials
```

What reaktor-tooling keeps after the move:

```
dev.shibasis.reaktor.tooling
  operation     InfrastructureOperation and NativeExecution: plan → approval → apply → receipt, over any cloud client
  mcp           the door
  build         GradleBuildPlanner, JvmProjectDiscovery, GradleSourceRoots, GradleReceiptRunLedger
  process       SupervisedProcessExecutor, ProcessQuery, NativeExecution
  device        DeviceTools, DeviceInspection
  source        KotlinSourceIndex, DefinitionDigestCache
  database      presentation over query results (plots, exports, value ordering)
  lsp · ide · git · atlas · checks · changes · runs · query · kernel · auth · io · delivery
```

## 2. Cloud provider layer

Highest leverage: the router, the sidecar, fastlane and the script moves all call it.

- **Move the existing clients first, without changing behaviour.** Moves go by package, never by directory: some modules keep a dotted and a nested source tree for the same package. Compiler-confirmed renames carry every use.
  - Cloudflare: CloudflareReader, CloudflareInventoryBuilder, CloudflareAi, D1JvmClient and WorkerJvmClient go to `cloud.cloudflare`.
  - Google: GoogleCloudReader, GoogleSignIn and PubSubJvmClient go to `cloud.google`.
  - Kubernetes: KubernetesReader, KubernetesJvmClient and `tooling.kubernetes` go to `cloud.kubernetes`.
  - Databases: DatabaseJvmClient and DatabaseWriteJvmClient split by engine into `cloud.postgres`, `cloud.memgraph` and `cloud.clickhouse`; SpanJvmClient goes to `cloud.clickhouse`.
    - Their request and receipt contracts move with them into `cloud.database`: the read statements, bound queries, query receipts, result comparison, plan operators, Memgraph pages, migration ledger and prefix inventory.
    - Presentation helpers stay in `tooling.database`: plots, exports and value ordering.
  - Firebase: CrashlyticsEvents and CrashlyticsReport go to `cloud.firebase`.
  - Shared: CloudApis, ApiSpecs and ApiSurface go to `cloud.api`; BoundedHttp and ServiceTokenClient go to `cloud.http`.
  - CloudApiTools stays in tooling, in `tooling.operation`. It turns API surfaces into tooling operations, so it is an operations concern.
  - The operation vocabulary stays in tooling and moves whole into `tooling.operation`: InfrastructureOperation, DatabaseConnection and the other sealed families, plus NativeExecution, which dispatches them. Kotlin keeps a sealed type's members in its package and module, so these families stay together. Each moved type keeps its current serialized name through `@SerialName`, so plans, fingerprints and receipts stay byte-identical.
  - Clients stop taking tooling types. Each takes the values it reads, either as parameters or as a small request class in its own platform package, and NativeExecution translates each operation into the call. Codex does this in place first, one client at a time, so the tree stays green; only then comes the single scripted move.
  - Platform-only request and response types go to their platform's package: the Worker protocol and binding requests, DurableObjectMutation, PubSubQuery and SpanReadReceipt.
  - Fully qualified references move with their types, as well as import lines.
  - reaktor-cloud gains its JS target in the same change, and dependeasy's `pnpmLock` task updates the workspace lockfile.
  - The kernel ops keep their names (`cloud.api.surfaces|search|describe|read|write|delete`) and call the new packages. The Cloud pane reads the same inventory.
- **One transport for every HTTP client.** It sets timeouts, retries only on 429 and 5xx, follows pages, keeps to rate limits, and keeps credentials out of logs and receipts.
- **Every documented endpoint is reachable.** Keep the cached OpenAPI and Discovery specs: Cloudflare (3,793 operations), Supabase (169) and 14 Google APIs. Add the cluster's own `/openapi/v3`, the Docker Engine spec and Grafana's spec. Reads go straight through; writes become operations.
- **New typed clients only where we act today through scripts:**
  - Docker Engine: images, containers and compose on m1-worker.
  - Grafana: dashboards, data sources, folders and alert rules.
  - Kubernetes: server-side apply of rendered manifests.
- **Every write is an operation.**
  - Plan: the exact request, plus a read-only diff against live.
  - Approval: bound to the plan's digest.
  - Apply, then a receipt that names the remote version.
  - Production writes need the owner, as today.
- **Credentials are resolved by name** through reaktor-secrets and the tracked config. They never appear in plans, receipts or logs.
- **Done when:**
  - `tooling.cloud`, `tooling.infra`, `tooling.api` and `tooling.kubernetes` are gone.
  - Each platform reads live through the kernel: `cloud.api.read` on Cloudflare, Google, Supabase, Kubernetes, Docker and Grafana, and one SQL or Cypher read each on Postgres, ClickHouse and Memgraph.
  - The Cloud pane shows the same resource and relation counts before and after, recorded side by side.

## 3. Build router in the kernel

- **Today:** `./gradlew` hands every build to `remote-dev gradle`, about 1,600 lines of Python. Helpers add about 2,700 more: `remote_build.py`, `build_control.py`, `workspace_storage.py`, `remote_retention.py`, `mobile_worker.py` and `atlas-worker.py`. About 3,700 lines of tests sit beside them. reaktor-cluster already has Kotlin leases, storage scans, hygiene, admission and host probes.
- **Target:** a `cluster.build` package in reaktor-cluster holds the router as kernel services:
  - choosing a host by load;
  - worker slots;
  - source sync, with rsync driven from Kotlin;
  - detached execution under a supervisor;
  - collecting and publishing outputs;
  - resume and cancel.
- **Docker Compose and port forwarding move into cloud clients.** They go to `cloud.docker` and the Kubernetes `PortForward`.
- **The kernel owns the one queue.** `./gradlew` becomes a few-line shim that runs the kernel's `reaktor build gradle` client. When the kernel is down, the client falls back to the local lease, as `gradle-lease` does today.
- **Finding, 6 Nov:** the Python suite imports `remote-dev`'s functions and patches them. It does not drive the CLI from outside, so it cannot check a Kotlin router unchanged. Each path needs Kotlin tests of its own and a live routed build; the on-disk protocol below is the compatibility contract.
- **The on-disk protocol stays during the move.** That covers `~/other/.remote-dev` (`config.json`, slots, `logs/`, `runs/`, `publications.sqlite3`) and each job directory (`request.json`, `control.json`, `stop.json`). Python and Kotlin paths can then run side by side, and old receipts stay readable.
- **Order:**
  1. Read-only paths: status, inspect, logs.
  2. Local routing.
  3. Worker routing.
  4. Publication and resume.
  5. Cancellation.

  Each path switches over once the Python suite and a live routed build pass. Its Python file then goes to the Trash.
- **Done when:**
  - The build path has no Python.
  - `remote-dev` and its helpers are in the Trash.
  - The `cluster.*` and `build.*` kernel ops drive the same runs.
  - m1-worker runs the same jobs with the same receipts.

## 4. Node sidecar on reaktor-graph

- **One Node process per kernel.** The kernel starts and supervises it and restarts it after a failure. It runs the dependeasy-pinned Node with its own pinned `node_modules`.
- **`kernelSidecar` inside the kernel module (owner, 6 Nov: "the sidecar code should be inside the kernel module").** The kernel itself stays a JVM module for now, so the sidecar is a nested multiplatform project, as `reaktorDesktopE2e` is nested in `reaktorDesktop`:
  - `commonMain`: the service contracts (`WranglerTools`, `SidecarHost`), the graph and its nodes, and the line framing — as much as possible;
  - `jsMain`: only the Node edges: the socket server, child processes, and loading a project's own Wrangler through the Karakum bindings;
  - `jvmMain`: the kernel's client, transport and supervisor.

  dependeasy's `nodeProgram()` builds it (done 6 Nov).
- **The link is reaktor-service's `SocketLink`** over a local socket. Calls are typed, long output streams back, and the kernel reads the sidecar's graph like any other graph.
- **Wrangler first.** The sidecar takes over:
  - deploys, versions, rollback and tail;
  - dev;
  - D1, KV and R2 access.

  It uses Wrangler's programmatic API where one exists, and runs Wrangler's command line inside the sidecar otherwise. Dependeasy's `WorkerDeployKernel` keeps plan, apply and receipt, and calls the sidecar instead of starting `wrangler` itself.
- **Other tools follow when a real consumer needs them.** Each becomes one node. Candidates: the docs-site scripts (`build-nav`, `build-site`, `diagrams`), Vite builds and Playwright runs.
- **Done when:**
  - No `wrangler` process starts outside the sidecar.
  - A Worker dev deploy runs plan then apply through the sidecar and leaves the same receipt.
  - Hangar shows the sidecar's graph.

## 5. fastlane in both directions

- **fastlane stays** for signing, building, and Firebase and store uploads.
- **dependeasy → fastlane already exists.** The release contract `build/dependeasy/release/<product>.json` is at schema 1. `release(output, lane)` nodes run lanes through `fastlane/run.sh`.
- **fastlane → reaktor is new.** A `reaktor` fastlane plugin in `reaktor/dependeasy/fastlane/plugin` adds actions that call the kernel:
  - `reaktor_plan` reads the release contract from the kernel, with no extra Gradle run;
  - `reaktor_gradle` runs a task through the kernel's router and streams its output;
  - `reaktor_secret` resolves a named secret for the lane and never writes it to disk;
  - `reaktor_step` reports each lane step, artifact digest, build number and upload id; these become receipts on the release node;
  - `reaktor_approval` waits for the owner's approval in Hangar before any store submission.
- **kernel → fastlane is new.** `ship.lanes` lists lanes from `fastlane lanes --json`. `ship.lane.run` runs a lane with receipts and streams it into Hangar's Deploy tab.
- **One typed contract.** It is declared once in dependeasy's model (schema 2) and generated for Ruby, so both sides read the same fields.
- **Checks use lanes that upload nothing:** build, signing check and size report. No App Store Connect or Play Console form, upload, submission or release happens during this work.
- **Done when:**
  - A lane started from Hangar and the same lane started from a terminal leave the same receipts on the release node.
  - Lanes read their contract and secrets from the kernel.

## 6. Scripts to Kotlin

- **k3s and observability:** the k3s scripts (37 files, about 4,900 lines) and the observability scripts (6 files, about 550 lines) become Kotlin operations over `cloud.kubernetes`, `cloud.google`, `cloud.grafana` and `cloud.docker`. Each operation has plan, apply and receipt. Helm stays a pinned tool for rendering charts, and the Kubernetes API applies the manifests.
- **Telemetry server:** the container and benchmark scripts (about 2,700 lines) become `cloud.docker` operations.
- **fastlane runners:** `fastlane/run.sh` and `dependeasy/fastlane/run.sh` go; the kernel resolves the locked Ruby bundle itself.
- **Order:** deploy and status paths first. Historical one-off scripts go to the Trash, not into Kotlin.

## 7. Readability

- **Packages per domain, files named after their main type.**
- **The kernel's flat package splits by domain.** `ai.bestbuds.reaktor.kernel` holds 90 files in one package today. It splits into:
  - `host`: service, owner, server, workspace, principal, access;
  - `door`: MCP endpoint, answers, events, inputs, tasks, presentation;
  - `agents`: identity, presence, inbox, memory, plans, runs, team;
  - `code`: atlas, code graph, source facts, changes;
  - `checks`: checks, check queue, coverage, testing history;
  - `build`: build runs, artifacts;
  - `cluster`: cluster, tasks, terminal, fleet, radar;
  - `cloud`: connections, providers, data catalog, infrastructure islands;
  - `telemetry`: telemetry, analytics, crashes, application logs;
  - `graph`: app graph, subgraphs, board, watch, catalog node, operations;
  - `sidecar`: new.
- **reaktor-cluster's flat package splits too:** its 20 files go into `cluster.host`, `cluster.storage`, `cluster.devices` and `cluster.build`.
- **No comments.** Names carry the meaning.
- **Least code.** Each step lands as moves first and behaviour changes second, so every diff reads as one thing.

## 8. Order of work and checks

| Step | Work | Decisive checks |
|---|---|---|
| A | Flip reaktor-cloud under tooling; move places to reaktor-service; move clients into platform packages (no behaviour change) | `:reaktor-cloud:build` on every target; all consumers compile; kernel and engine tests; live `cloud.api.read` per platform; Cloud pane counts before and after |
| B | Shared transport; writes as operations; Docker, Grafana and Kubernetes apply clients | One dev write per platform through plan, approval and receipt |
| C | Router read-only paths, then local and worker routing, publication, resume, cancel | Python suite against the Kotlin router; live routed builds on both hosts |
| D | reaktor-sidecar with Wrangler | `:reaktor-sidecar:build` on JVM and JS; Worker dev deploy plan and apply through the sidecar; same receipt |
| E | fastlane plugin and `ship.lane*` ops | Non-uploading lane from Hangar and from a terminal; same receipts |
| F | Scripts to Kotlin, by use | Each replaced script's status and dev paths through the kernel |
| G | Kernel and cluster package split | Compile, kernel tests, MCP door catalog unchanged |

- Codex implements in the one persistent thread. Claude reviews every diff and runs the decisive checks.
- Hangar is reinstalled and the host upgraded after each visible step. Host upgrades wait until the host has no build children and no active runs.
- Commits only on the owner's word, and only these paths.

## Defaults taken (owner can change)

- Module name `reaktor-sidecar`.
- The fastlane plugin lives in `reaktor/dependeasy/fastlane/plugin`.
- Helm stays as a pinned chart renderer; the Kubernetes API applies.
- Postgres, ClickHouse and Memgraph admin clients live in reaktor-cloud; reaktor-db keeps the app's runtime data layer.
