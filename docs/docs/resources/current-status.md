# Current State: Honest Audit

Before embarking on the roadmap to become the "Unreal Engine" of app development, it's essential to understand where Reaktor stands today.

## Module Maturity Matrix

| Stability | Modules |
| --- | --- |
| **Stable (Production)** | `reaktor-core`, `reaktor-graph`, `reaktor-graph-port`, `reaktor-io`, `reaktor-auth`, `reaktor-db`. |
| **Experimental** | `reaktor-cloudflare`, `reaktor-google`, `reaktor-work`, `reaktor-ffi`, `reaktor-flexbuffer`. |
| **Early** | `reaktor-ui`, `reaktor-media`, `reaktor-location`, `reaktor-telemetry`. |
| **Brainstorming/Paused**| `reaktor-web`, `reaktor-notification`, `reaktor-tactile`, `reaktor-react`. |
| **Tooling** | `dependeasy`, `reaktor-compiler`, `reaktor-mcp`. |

## Key Strengths

-   **Graph-first Architecture**: A genuinely novel approach to KMP development.
-   **Capability Composition over Inheritance**: Provides an elegant mixin system for lifecycle, DI, and navigation.
-   **Shared Service Contracts**: A massive productivity multiplier for client-server communication.
-   **FlexBuffer Investment**: A unique performance moat with its zero-copy serialization.
-   **Real-world Validation**: Battle-tested by flagship products like **Bestbuds**.

## Identified Gaps & Improvements

After a comprehensive audit, the following critical gaps have been identified and prioritized for Phase 1 and 2:

| Gap | Impact | Recommendation | Priority |
| --- | --- | --- | --- |
| **No Documentation Site** | A framework without docs is a framework without users. | **Action**: Build and launch this Docusaurus site. | **Critical** |
| **No CLI or Scaffolding** | First-touch developer experience is currently nonexistent. | **Action**: Build `reaktor-cli` (`npx create-reaktor-app`). | **Critical** |
| **Schema DSL Specification** | Ad-hoc codegen is inconsistent and difficult to maintain. | **Action**: Formalize the Schema DSL grammar and validation rules. | **Critical** |
| **Offline-first Conflicts** | `ObjectStore` uses a simple "server wins" policy, losing data. | **Action**: Implement CRDT-based merge for key data types. | **High** |
| **No Integration Testing** | A schema change in Kotlin can break TypeScript codegen. | **Action**: Build a cross-runtime test harness to run in CI. | **High** |
| **No Publishing Pipeline** | External developers cannot currently depend on Reaktor. | **Action**: Automate publishing to Maven Central and npm. | **High** |
| **Mesh Discovery Abuse** | A malicious peer can flood namespace registrations. | **Action**: Add per-peer rate limits in the Durable Object. | **High** |
| **No Observability for FFI**| Cross-language calls are a "black box" to telemetry. | **Action**: Instrument the C++ ABI surface with OpenTelemetry spans. | **Medium**|
