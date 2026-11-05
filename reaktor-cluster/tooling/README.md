# Cluster tooling

`reaktor-cluster` owns the cluster model and graph, host inspection, admission,
storage policy, and these native execution adapters. Hangar and the CLI consume
the module; the existing `tools/` entries are compatibility links to this
directory. There is one source for each adapter.

## Commands and ownership

| Source | Responsibility |
| --- | --- |
| `remote-dev` | Load-based Gradle routing, worker slots, source synchronization, detached execution, output collection/publication and resume; Docker Compose and port forwarding |
| `remote_build.py`, `gradle-lease` | Host leases, fair admission queue, frozen source snapshots and isolated execution receipts |
| `hosts_contract.py` | Dependeasy's generated `build/dependeasy/remote/hosts.json` contract |
| `workspace_storage.py`, `remote_retention.py`, `dev-storage`, `storage-limits.json` | Disk budgets, protected active data and expiring generated payloads |
| `mobile_worker.py`, `setup-mobile-worker.py`, `mobile-device-hook.py`, `physical-devices.gradle` | Physical-device routing, tool setup, suite-owned iOS signing and simulator exclusion |
| `atlas-worker.py` | Read-only Atlas analysis in worker slot 2; mutation and runtime authority remain with the local workspace owner |
| `run-gradle` | Worker process lease, mobile environment and durable exit status |
| `build-cache.gradle`, `setup-build-cache.py` | Explicitly selected cache access and private credential provisioning |
| `cluster_locations.py`, `install-links.py` | Canonical asset/workspace/state locations and compatibility links |

The native adapters preserve the established execution protocol. They do not
introduce a second Gradle scheduler. Dependeasy remains the authority for declared
task hosts, runtime requirements, output families and bundle ownership.

## State and policy

Module source and runtime state are separate. The default workspace layout is
`<workspace>/reaktor/reaktor-cluster/tooling`. The source workspace remains
`~/dev`; state may remain at `~/dev/.remote-dev` or be relocated to
`~/other/.remote-dev`. `state_root()` first selects a regular `config.json` in
the legacy state directory, then the relocated directory. Without either config,
it selects an existing legacy directory, then an existing relocated directory,
then the legacy fallback. An empty directory recreated by an older process
cannot hide relocated configuration. Resolving a compatibility symlink retains
the canonical source workspace. `REMOTE_DEV_WORKSPACE_ROOT` retains its
existing meaning: select a registered execution workspace without relocating
the primary state or leases.

Keep `.remote-dev/config.json`, lock files, slot directories, `logs/`, `runs/`,
snapshots and `publications.sqlite3` in place. Machine addresses and private
environment values stay in that private configuration. Existing configured
admission thresholds take precedence over adapter defaults.

Local and worker builds retain their own leases. Worker slot 0 owns the attached
phones; additional slots require the configured memory and disk capacity. Mobile
commands and suites run on the worker, while declared desktop checks stay local.
No adapter starts an emulator or simulator. Storage cleanup preserves source,
credentials, open files and active jobs.
Moved `~/other/{tmp,temp,.tmp}` and moved project temporary directories remain
charged to the same combined temporary budget as the source workspace.

## Compatibility and verification

Repository `gradlew` wrappers locate the module router directly and ask
`cluster_locations.py configured` whether configured routing is available.
`cluster_locations.py state-root` prints the selected state directory. Existing
Python imports and source-inspecting fixtures remain usable through compatibility
links in `~/other/tools` after relocation, or legacy `<workspace>/tools` otherwise.

```sh
python3 reaktor/reaktor-cluster/tooling/install-links.py --check
python3 -m unittest discover -s reaktor/reaktor-cluster/tooling -p 'test_*.py'
reaktor/reaktor-cluster/tooling/remote-dev status
```

On a new workspace, `install-links.py` creates missing links. It checks the whole
set before writing and refuses to replace a regular file or a different symlink.
It repairs the known former relative compatibility links after `tools/` moves
beside the relocated state; it does not recreate `~/dev/tools` in that layout.
Compare existing workspace helpers with the canonical sources before a cutover.

Moving these sources does not restart services, install worker assets, change
machine configuration or disturb a running job. The worker's deployed
`.remote-dev/run-gradle`, init scripts and mobile hook remain runtime artifacts.
Only update them while their host is idle. Atlas's existing `--install` command
still installs its standalone worker transport; normal source synchronization
then carries the module-owned assets as part of the Reaktor repository.

The dated `tools/cleanup-worker.py`, `migrate-docker.py`, `restore-docker.py` and
`validate-worker.py` scripts are historical one-off operations, not active cluster
execution adapters. They remain outside the module.
