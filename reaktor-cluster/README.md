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

```sh
./gradlew :reaktor-cluster:jvmTest --console=plain
./gradlew :reaktor-cluster:clusterDist --console=plain
```

The distribution provides `reaktor-cluster status|jobs|storage|hygiene --json`.
Status and jobs use the same typed service path as Hangar. Storage/hygiene preserve
their existing reviewed report and receipt flows.
