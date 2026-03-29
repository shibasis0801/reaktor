# reaktor-core

`reaktor-core` is the lowest shared runtime layer in Reaktor.

## Responsibilities

- adapter base classes
- feature registry
- capability primitives
- cross-platform runtime helpers
- common network primitives such as `Request`, `Response`, and `StatusCode`
- platform-specific helpers for permissions, storage, dispatch, lifecycle integration, and weak references

## Key concepts

### Adapter

`Adapter<Controller>` is the main controller bridge used throughout the framework.

It provides:
- weakly-held controller references
- synchronous and suspend invocation helpers
- consistent null-controller failure behavior

### Feature registry

`Feature` is the global slot registry used to install shared platform services such as:
- auth
- database
- SQL
- storage
- permissions
- Google Pub/Sub

### Capability base

Capabilities are the reusable behaviors layered into graphs and nodes:
- `ConcurrencyCapability`
- `LifecycleCapability`
- `DependencyCapability`
- other higher-level capabilities build on the same pattern

## When to put code here

Put code in `reaktor-core` only if it is:
- product-neutral
- graph-neutral or foundational to graph/service layers
- needed by multiple higher-level modules

If the code depends on graph semantics, it usually belongs in `reaktor-graph` instead.
