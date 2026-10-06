# reaktor-db

> **Status: implementation varies by API and target; browser SQLite migration awaits release qualification.**

`reaktor-db` contains local persistence APIs, SQL/graph adapters and query-policy helpers. The complete portable data platform and general offline sync engine remain proposed.

The canonical documentation is the [Data layer reading map](https://reaktor.build/docs/reaktor-db#reading-map). Read [current storage APIs](https://reaktor.build/docs/reaktor-data-storage) for implemented surfaces, [Queues and Workflows](https://reaktor.build/docs/reaktor-data-workflows) for the cloud execution profile and [implementation gates](https://reaktor.build/docs/reaktor-data-implementation) for qualification.

## Responsibilities

- Object database abstraction for app-side persistence
- Object stores and singleton observable object states
- Memory-only object cache and persisted-object retention policies
- Repository support for offline-first usage (read-through, write-through)
- Graph database policy helpers for tenant-safe Cypher execution
- Apollo Kotlin GraphQL client integration for shared KMP callers

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web have source sets and adapters. Their existence does not prove equal persistence or background-execution guarantees.

The current browser source uses `openWebSqliteObjectDatabase`, a SQLite/Wasm Worker, OPFS and Web Locks. Legacy IndexedDB records are imported without replacing newer SQLite keys; a separate marker prevents reset from reimporting old records. Publish the worker/Wasm assets and qualify migration, reload, tab concurrency, quota and account isolation on supported browsers. This is local persistence, not remote backup.

## Key types

### Object database

| Type | Purpose |
|---|---|
| `ObjectDatabase` | Consistency boundary with final public operations, backend raw operations, and event emission |
| `ObjectStore` | Singleton namespace with typed object access and singleton `ObjectState<T>` handles |
| `ObjectState<T>` | Live `StateFlow` backed handle for one stored object; writes go through suspend `set`, `update`, and `delete` |
| `StoredObject<T>` | Wrapper with key, value, storeName, timestamps |
| `JsonSqliteObjectDatabase` | Concrete implementation: JSON + SQLite storage |

### Cache and retention

| Type | Purpose |
|---|---|
| `ObjectCache` | Memory-only object cache interface |
| `LruObjectCache` | LRU memory cache with entry and byte limits; eviction never deletes persisted data |
| `RetentionPolicy` | Persisted object validity policy |
| `KeepForever` / `ExpireAfter` | Built-in retention policies |

### Graph database policy

| Type | Purpose |
|---|---|
| `GraphDbPolicy` | Tenant safety enforcement for Cypher queries |
| `MandatoryTenantParameterization` | Validates `$tenant_id` injection in all Cypher queries |
| `MemgraphInspection` | Closed catalog of bounded label, property, node, relationship and count reads for an authorized database inspector |
| `MemgraphReadPage` | Graph-neutral, bounded node/relationship result identities, returned properties and explicit unloaded endpoints |

The graph DB surface binds tenant parameters and validates supported query shapes. It is a guardrail, not a proof for arbitrary Cypher, procedures or direct-driver access. Admitted executors still enforce authority and qualified query policy.

`MemgraphInspection` is a pure query catalog, separate from tenant-facing graph access. Its parser accepts
only a registered statement with a limit of 1–500 and an offset of 0–1,000,000. Callers must still bind
the query to an authorized target and enforce their transport boundary. BestBuds Desktop independently
validates this catalog in its cluster query broker; it does not accept free-form Cypher through that path.
Node and relationship pagination orders by internal ID, whose identity is local to the database snapshot.

`MemgraphReadPage` validates registered node/relationship result columns and keeps parallel edges and
self-loops. It rejects malformed or duplicate identities. A relationship row establishes its endpoint IDs,
not their labels or properties; those endpoints remain `loaded = false`. Desktop supplies an optional
`reaktor-flow` projection of the returned page, with no automatic join to the mounted application graph.

### SQL and sync

| Type | Purpose |
|---|---|
| `SqlAdapter` | SQL adapter pattern |
| `SyncAdapter` | Whole-database HTTP snapshot upload/download and restore; no general incremental sync or conflict protocol |

### GraphQL client

| Type | Purpose |
|---|---|
| `GraphQlClient` | Reaktor GraphQL client abstraction for generated Apollo operations |
| `ApolloKmmGraphQlClient` | Apollo Kotlin backed implementation for queries, mutations, and subscriptions |
| `Feature.GraphQl` | Global feature slot for the configured GraphQL client |

## Dependencies

- `reaktor-io`
- SQLDelight (runtime + platform-specific drivers: Android, iOS native, JDBC, SQLite)
- Apollo Kotlin runtime
- Neo4j Java driver (server only)
- kotlinx-coroutines-jdk8 (server)

## What this module is not

`reaktor-db` is not trying to be a universal ORM. It is the shared persistence substrate for Reaktor products.
