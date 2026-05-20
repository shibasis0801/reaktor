# Reaktor FFI: The Universal Language Bridge

Reaktor FFI (`reaktor-ffi`) is the layer that allows any language runtime to interface with Reaktor's graph. The core is written in C++ (not C) to leverage templates, RAII memory management, and SIMD-accelerated operations.

## Architecture

The FFI layer has three components:

1.  **C++ Core (libreaktor)**: A C++ library that exposes Reaktor's graph operations and FlexBuffer manipulation. It is a first-class graph runtime alongside Kotlin and TypeScript.
2.  **C++ ABI Surface**: A thin `extern "C"` layer on top of the core that provides a stable ABI for language bindings to link against. It accepts and returns FlexBuffer byte arrays as the universal interchange format.
3.  **Language Bindings**: Each language wraps the C++ ABI surface in idiomatic APIs, handling memory management (RAII), threading, and type mapping.

## Three First-Class Graph Runtimes

The graph abstraction (nodes, ports, edges, lifecycle) is implemented in three languages:

| Runtime | Language | Use Case | Interop Mechanism |
| --- | --- | --- | --- |
| **Primary** | Kotlin (commonMain) | Business logic, services, and data layer. Compiles to JVM, Native, and JS. | In-process on all Kotlin targets. No FFI needed. |
| **Performance**| C++ | SIMD algorithms (via Highway), numerically sensitive computation, and real-time audio/video. | FlexBuffer FFI via C++ ABI surface. |
| **Dynamic** | TypeScript | Web UI logic, Cloudflare Workers, and Hermes-based CodePush mobile logic. | FlexBuffer FFI via Hermes C++ API (mobile) or in-process (V8/Workers). |

## Performance Portability with Google Highway

The C++ graph runtime uses Google Highway for SIMD-accelerated compute:

-   **Performance Portability**: The same source code targets x86 (SSE4, AVX2, AVX-512), ARM (NEON, SVE), POWER, WASM, and RISC-V.
-   **5-10x Speedups**: For vectorizable workloads like matrix operations, convolutions, and encoding/decoding.
-   **Header-only**: C++17 with no heavy dependencies. It integrates directly into the `libreaktor` build.

## Language Bindings Roadmap

| Language | Bridge Mechanism | Status | Primary Use Case |
| --- | --- | --- | --- |
| **Kotlin/JVM** | In-process | Stable | Android apps, Spring Boot servers. |
| **Kotlin/Native**| In-process | Stable | iOS apps, native desktop. |
| **Kotlin/JS** | In-process | Stable | Web apps, Cloudflare Workers. |
| **C++** | In-process | Experimental | SIMD compute, neural networks. |
| **TypeScript** | In-process / Hermes | Experimental | Web, Workers, CodePush, React Native. |
| **Dart (Flutter)**| C++ ABI via `dart:ffi`| Planned (Q4 2026) | Flutter bridge. |
| **Swift** | C++ ABI via Swift C interop | Planned (Q1 2027) | SwiftUI bridge. |
| **Rust** | C++ ABI via `extern "C"` | Future | Systems programming, WASM. |
| **Python** | C++ ABI via `pybind11` | Future | ML/AI pipelines, data science. |
