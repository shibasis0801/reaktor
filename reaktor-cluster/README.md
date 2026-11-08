# Reaktor Cluster

`reaktor-cluster` owns the native model for developer machines, measured capacity,
build admission, workloads, terminal targets, storage and process hygiene. Hangar,
the workspace kernel and the CLI consume this model.

`ClusterApi` is the typed `reaktor-service` contract. `ClusterRuntime` serves it in a
`reaktor-graph` and connects the reader through a consumer port. The read operations
retain source timestamps, missing values and partial/failed observations. The
workspace kernel owns caller authority; the service does not create a second server
or scheduler.

`ClusterReader` reads `.remote-dev/config.json`, router receipts and actual native
leases. `HostProbe` measures local capacity and the configured SSH worker. Its
process observations contain executable basenames, PID, CPU and resident memory for
all processes visible to the local or SSH user, with a bounded page of 64 busiest
processes. Process arguments and environment values are excluded. Missing process
observations do not erase capacity.

Repositories remain in `~/dev`; this machine's shared state now lives in
`~/other/.remote-dev`. `ClusterLocations` prefers a configured legacy
`~/dev/.remote-dev`, then the configured relocated state, then existing state
directories. It retains `~/dev` as the source workspace when state is relocated.
Temporary files under both `~/dev` and `~/other` remain in the combined temp budget.

`ClusterGraph(status, jobs, tasks)` is a snapshot helper that joins host, slot,
process, routed-job and native-task identities by typed host ports. These snapshot
nodes are not attached to the workspace's production graph. Production calls use
the service graph described above. A job's requester seat, session and plan item
remain recorded facts; an unmatched or missing owner remains unknown.

Native Reaktor tasks use `ClusterApi.tasks` separately from routed build receipts.
The workspace kernel injects an owner-local summary reader through
`ClusterRuntime(readTasks = ...)`; it attributes tasks to a machine through an
in-process owner or an existing local owner's verified descriptor, held lock and
process identity. Remote profiles do not establish local ownership. Standalone
runtimes report this source as unavailable. Recorded provider states and bounded-page
qualifications remain visible to callers.

`ClusterApi.devices` is a bounded live read of the configured SSH worker through
`ClusterDeviceReader` and the canonical `remote-dev mobile inventory` route. It
reuses the existing cluster configuration and mobile environment. `adb devices -l`
retains unauthorized/offline devices; raw CoreDevice JSON retains paired Apple
records that Maestro omits. Host-qualified identities, model/OS facts when known,
transport state and per-transport failures are returned without pairing, connecting,
launching or changing phones. A successful current query can contain a cached
Apple record: disconnected rows have `connected=false`, `truth=Stale` and the
recorded last connection date. Pairing and developer mode never imply a live tunnel
or an app-ready automation session. Existing local DevTools discovery remains its
own graph-owned source. Each native probe has an eight-second deadline; transport
captures and the thirty-second routed command are bounded, and worker JSON files
are removed after success or failure.

`ClusterTerminal.command(host)` returns a local login shell or an interactive SSH
command for exactly the worker configured in the cluster. Hangar gates and launches
the terminal; arbitrary commands and host strings are not accepted by this helper.

The module's [native tooling](tooling/README.md) owns source synchronization,
Gradle routing/execution, artifact publication, physical-device transport and
storage policy. Existing `~/other/tools` commands are compatibility links to those
canonical sources; repository Gradle wrappers retain their entry points.
`Admission` contains native capacity/lease admission while the Python adapter
executes the established transport protocol. They share the existing `.remote-dev`
configuration, receipts and leases. Local and remote jobs publish their receipt
when execution starts and update the same receipt when it finishes.

`ClusterSettings` reads and updates build concurrency defaults through
the same configuration file. Updates carry the revision that was reviewed, reject
conflicts, preserve unrelated keys and file permissions, and publish atomically.
Hangar can set **This Mac** to one or two lanes independently of the worker.
The first local lane uses the main checkout. When it is busy and the Mac has
capacity, the second can run declared portable verification in a separate source
snapshot, with up to four Gradle workers. It retains the results under the run's
artifacts directory. Deploys, desktop launches and device tasks keep their
existing routes. Removing the second lane lets its current build finish; it
stops taking new builds. Gradle worker counts do not reserve CPU cores.

`ClusterControls` runs the typed inspect/stop/resume operations in process through
`cluster.build.BuildControl`. It reads the receipts and job directories that the
router's supervisor (`tooling/build_control.py _supervise`) writes, on this Mac or,
for worker builds, through one ssh shell command per step. Stop requires a recorded
execution ID and an owned supervisor, checks the identity again at mutation time,
and asks that supervisor through `stop.json` to stop its isolated build process
group; the controller itself signals nothing. Old receipts cannot establish that
authority. Cancellation is recorded explicitly and prevents artifact collection
and publication. Detached Gradle/Kotlin daemons can remain. GUI resume currently
reports unsupported; the existing Terminal recovery command remains available.

Execution and publication are separate facts. A build can execute on the worker,
release its worker slot, and then publish through the primary machine. `Job.phase`,
`executionHost`, `phaseHost` and qualified blockers preserve this distinction;
missing receipt facts do not create a guessed owner or phase.

Hangar's Devices view compares machine cards and measured resource summaries,
shows actual build lanes and active work cards, and opens selected-object details.
Process inspection and build history use sortable tables. Configuration has a
draft/review/apply flow; Stop confirmation stays bound to the execution reviewed.
Native tasks are qualified as belonging to this workspace, while routed builds
cover all projects recorded by the shared router. Polling runs only while the
Machines view is visible, focused and enabled, with observation age shown.

The interaction model draws on the separation of physical nodes and logical work
in the [Ray dashboard](https://docs.ray.io/en/latest/ray-observability/getting-started.html),
linked job/allocation/node inspection in
[Nomad](https://developer.hashicorp.com/nomad/docs/monitor/inspect-workloads),
selected-object controls in
[Docker Desktop](https://docs.docker.com/desktop/use-desktop/container/), and
machine/process drilldown in
[Netdata](https://learn.netdata.cloud/docs/live-view/processes). Resource displays
retain the OS meaning of each signal: memory free is not memory pressure, disk
free is not total utilization, and a Gradle worker is not a CPU reservation.

```sh
./gradlew :reaktor-cluster:jvmTest --console=plain
./gradlew :reaktor-cluster:clusterDist --console=plain
```

The distribution provides `reaktor-cluster status|jobs|storage|hygiene --json`.
Status and jobs use the same typed service path as Hangar. Storage/hygiene preserve
their existing reviewed report and receipt flows.
