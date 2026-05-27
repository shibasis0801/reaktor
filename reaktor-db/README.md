# reaktor-db

> **Stability: Stable**

`reaktor-db` contains Reaktor's data storage abstractions for offline-first apps and tenant-safe server queries.

## Responsibilities

- Object database abstraction for app-side persistence
- Object stores and singleton observable object states
- Memory-only object cache and persisted-object retention policies
- Repository support for offline-first usage (read-through, write-through)
- Graph database policy helpers for tenant-safe Cypher execution
- Apollo Kotlin GraphQL client integration for shared KMP callers

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

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

The graph DB surface adds soft multi-tenancy through mandatory parameterization, intended for graph databases like Memgraph where tenant isolation is enforced by query shape.

### SQL and sync

| Type | Purpose |
|---|---|
| `SqlAdapter` | SQL adapter pattern |
| `SyncAdapter` | Synchronization support |

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
