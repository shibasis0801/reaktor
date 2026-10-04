# reaktor-tooling

[Workbench layer architecture](https://reaktor.build/docs/reaktor-workbench-layers) explains how this module relates to tooling, the kernel and its hosts.

Open-source, graph-agnostic tool integration for Reaktor hosts. Common code defines discovery, task, plan, approval, event and receipt contracts. JVM adapters target Java 21 and do not depend on Compose, a renderer or a product backend.

| Surface | API |
| --- | --- |
| Workspace task discovery and sealed bindings | `JvmProjectDiscovery`, `DiscoveredJvmWorkspace` |
| Supervised argv execution and cancellation | `SupervisedProcessExecutor`, `ProcessExecutionRequest` |
| Bounded read-only process discovery | `ProcessQuery` |
| Android/iOS device integration | `DeviceTools`, `DeviceInspection` |
| Kotlin source declarations | `KotlinSourceIndex` |
| File-based Git revision reads | `git.readWorkspaceRevision` |
| JetBrains IDE integration | `ide.ReaktorIde` |
| Kubernetes manifest and observed-object inspection | `kubernetes.KubernetesManifestIndex`, `KubernetesInspection` |
| Bounded Memgraph catalog/results | `database.MemgraphInspection`, `MemgraphReadPage` |
| Heimdall directory read definitions | `auth.AuthDirectoryQueries` |
| Local MCP transport and read client | `mcp.LoopbackMcpServer`, `LoopbackMcpReadClient` |

The host supplies task policy, workspace context, credentials and any approvals. This module supervises external execution; it does not choose product environments, approve work, define product workflows or depend on BestBuds. Processes use argv vectors and the existing supervised executor, including source-definition seals, bounded structured capture, cancellation and timeout cleanup.

MCP's transport-independent protocol remains in `reaktor-mcp`. Tooling wraps HTTP on loopback, validates Host and Origin, bounds request bodies, and owns its server lifetime. Read-tool definitions and redaction remain host responsibilities. Close the returned server and any process executor when their owning session ends.

```sh
./gradlew :reaktor-tooling:jvmTest
./gradlew :reaktor-tooling:verifyToolingBoundary
```

Optional graph integration belongs in a host adapter: plain tooling contracts can be provided through graph ports without making callers depend on the graph or GUI.

## Native JVM infrastructure

`infra.InfrastructureOperation` describes Kubernetes reads, database reads and
Worker operations without argv. `NativeExecutionRequest` seals their definition,
credential context and bounds into the plan fingerprint. `JvmInfrastructureExecutor`
executes with a cancellation-owned `InfrastructureSession`; plain clients are
also available to library callers.

| Client | Transport |
| --- | --- |
| `KubernetesJvmClient` | Official Kubernetes Java client, bounded inventory/events/logs, named secrets and API port forwards |
| `DatabaseJvmClient` | PostgreSQL JDBC, Memgraph Bolt, ClickHouse HTTP |
| `WorkerJvmClient` | HTTPS, short-lived service authentication and versioned operation discovery/receipts |

PostgreSQL accepts conventional `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`,
`PGPASSWORD` and optional `PGSSLMODE` fields. Sealed SQL sessions additionally
require `REAKTOR_PG_INSPECTOR_POLICY=least-privilege-inspector-v1` and verify the
effective database role; an attestation string alone is insufficient. Product
environment/credential mapping belongs to the host. Memgraph accepts only the
registered `MemgraphInspection` catalog. Private query/result files, lookahead
rows, server read settings and response bounds preserve session isolation.

Worker contracts live in common code: `WorkerOperationCatalog`,
`WorkerOperationDescriptor`, `WorkerOperationResult`. The JVM client accepts only
advertised bounded reads and validates worker/environment/request identity.
These clients do not grant tenant authority or implement product administration
policy. Kubernetes exec/auth-provider credential plugins are intentionally
unsupported; configure certificate or token credentials for the native path.
