# reaktor-db

> **Stability: Stable**

`reaktor-db` contains Reaktor's data storage abstractions for offline-first apps and tenant-safe server queries.

## Responsibilities

- Object database abstraction for app-side persistence
- Object stores and observable flows
- Cache policies (LRU, TTL)
- Repository support for offline-first usage (read-through, write-through)
- Graph database policy helpers for tenant-safe Cypher execution

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

## Key types

### Object database

| Type | Purpose |
|---|---|
| `ObjectDatabase` | Abstract persistence base with event emission (Put, Get, Delete, Clear) |
| `ObjectStore` | Typed object access with read/write semantics |
| `ObjectFlow<T>` | Observable reactive flows for stored objects |
| `StoredObject<T>` | Wrapper with key, value, storeName, timestamps |
| `JsonSqliteObjectDatabase` | Concrete implementation: JSON + SQLite storage |

### Cache policies

| Type | Purpose |
|---|---|
| `CachePolicy` | Interface for cache eviction strategies |
| `CachePolicyLRU` | LRU implementation with time-based expiration (TTL) |

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

## Dependencies

- `reaktor-io`
- SQLDelight (runtime + platform-specific drivers: Android, iOS native, JDBC, SQLite)
- Neo4j Java driver (server only)
- kotlinx-coroutines-jdk8 (server)

## What this module is not

`reaktor-db` is not trying to be a universal ORM. It is the shared persistence substrate for Reaktor products.
