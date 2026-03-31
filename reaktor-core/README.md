# reaktor-core

> **Stability: Stable**

`reaktor-core` is the lowest shared runtime layer in Reaktor. Every other module depends on it.

## Responsibilities

- Adapter base classes for platform bridging
- Feature registry for global service slots
- Capability primitives (lifecycle, concurrency, dependency injection)
- Cross-platform runtime helpers
- Common network primitives: `Request`, `Response`, `StatusCode`
- Platform-specific helpers for permissions, storage, dispatch, lifecycle, and weak references

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

## Key types

### Adapter

`Adapter<Controller>` is the main controller bridge used throughout the framework.

It provides:
- Weakly-held controller references via `WeakRef<Controller>` (safe for mobile lifecycles)
- Synchronous invocation: `invoke { controller.doSomething() }`
- Suspend invocation: `suspended { controller.fetchData() }`
- Result-aware variants: `invokeResult`, `suspendedResult`
- Consistent null-controller failure behavior when the controller is garbage collected

### Feature registry

`Feature` is the global singleton slot registry. Platform services are installed at startup and accessed from shared code:

```kotlin
// Install at startup (platform-specific)
Feature.Auth = AndroidAuthAdapter(activity)
Feature.Database = SqliteObjectDatabase(context)

// Access from shared code (any platform)
val auth = Feature.Auth
```

Available slots include `Auth`, `Database`, `Sql`, `Storage`, `Permission`, `GooglePubSub`, `Telemetry`.

New slots are created with the `CreateSlot<T>` property delegate:
```kotlin
var Feature.MyService by CreateSlot<MyServiceAdapter<*>>()
```

### Capabilities

Capabilities are reusable behavior mixins composed into graphs and nodes via delegation:

| Capability | Purpose |
|---|---|
| `ConcurrencyCapability` | Owns a `CoroutineScope` and dispatcher; provides `launch`, `async`, `execute`, `withContext` |
| `LifecycleCapability` | State machine: Created, Restoring, Attaching, Saving, Destroying |
| `DependencyCapability` | Scoped DI access |
| `AtomicCapability` | Thread-safe close via atomicfu |

### Network primitives

- `Request` / `Response` - Base HTTP shapes
- `StatusCode` - HTTP status enum (OK, BAD_REQUEST, UNAUTHORIZED, etc.)
- `JsonResponse` - Serializable response wrapper

### Platform adapters

- `PermissionAdapter` - Permission request/result handling (Android, iOS)
- `StorageAdapter` - File system access
- `FileAdapter` - Cross-platform file I/O
- `Dispatch` - Platform-specific dispatcher access (main/UI thread)

## Dependencies

- `kotlinx-coroutines` - Structured concurrency
- `kotlinx-serialization` - JSON serialization
- `kotlinx-datetime` - Date/time utilities
- `atomicfu` - Lock-free thread safety
- `kotlinx-collections-immutable` - Persistent collections
- `kermit` - Cross-platform logging

## When to put code here

Put code in `reaktor-core` only if it is:
- Product-neutral
- Graph-neutral or foundational to graph/service layers
- Needed by multiple higher-level modules

If the code depends on graph semantics, it belongs in `reaktor-graph` instead.
