# reaktor-security

`reaktor-security` is the high-level Reaktor conversation encryption module. It exposes conversation encryption APIs and internally models every secure conversation as an MLS group using Cisco MLS++ (`mlspp`).

It intentionally does not expose Signal-like abstractions, raw MLS state, raw private keys, group secrets, nonces, or ciphersuite internals.

Current implementation status:

- native C++ MLS wrapper around Cisco MLS++ for key packages, group creation, welcomes, commits, and application encryption
- stable C ABI for Kotlin/Native, JNI, desktop, and WASM bindings
- Kotlin common API surface under the normal Reaktor KMP `src/*Main` layout
- TypeScript API surface under the normal Reaktor `ts/` layout, including Karakum back-import setup
- host CMake and GoogleTest coverage under `cpp/tests/native` for direct conversation and member add flow

The next hard blocker is durable MLS state snapshots. MLS++ exposes `State` internals as protected fields but does not currently expose a clean public durable-state serializer. The intended path is a small audited MLS++ patch adding `State::export_snapshot()` and `State::import_snapshot()`.

The implementation plan is consolidated in the repository root at `plan.html`.
