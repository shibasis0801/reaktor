# reaktor-db

`reaktor-db` contains Reaktor's data storage abstractions.

## Responsibilities

- object database abstraction for app-side persistence
- object stores and observable flows
- cache policies
- repository support for offline-first usage
- graph database policy helpers for tenant-safe Cypher execution

## Current supported areas

### Object database

The current app-facing storage model is based on:
- `ObjectDatabase`
- `ObjectStore`
- `ObjectFlow<T>`
- cache policy implementations such as TTL/LRU behavior

### SQL and repository integration

Higher-level repositories can layer read-through and write-through caching on top of the object database.

### Graph database policy

The current graph DB surface adds soft multi-tenancy through mandatory parameterization.

The framework can enforce:
- mandatory `$tenant_id`
- query inspection before dispatch
- automatic binding from the execution context

This is intended for graph database usage such as Memgraph, where tenant isolation is enforced by query shape rather than separate physical databases.

## What this module is not

`reaktor-db` is not trying to be a universal ORM. It is the shared persistence substrate for Reaktor products.
