# Reaktor graph runtime

[Workbench layer architecture](https://reaktor.build/docs/reaktor-workbench-layers) explains how this module relates to tooling, the kernel and its hosts.

Headless Kotlin Multiplatform graph execution: nodes, typed wiring, actors, capabilities, navigation, visitors and dependency adapters. JVM consumers require Java 25, matching the framework toolchain.

This module owns the existing `dev.shibasis.reaktor.graph` runtime classes. `reaktor-graph` re-exports them and adds Compose bindings and UI rendering, preserving existing source imports. `collectPayloadAsState` and `ComposeContainerNode` remain in the UI module. Headless hosts depend directly on `reaktor-graph-runtime` and `koin-core`; the runtime has no Compose, Skiko or product dependency.

`verifyRuntimeBoundary` validates the JVM runtime dependency graph and is part of `check`. Runtime and UI share graph identities, serialization and typed ports rather than translating between separate graph implementations.
