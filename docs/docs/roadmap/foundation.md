# Execution Roadmap

To become a platform, Reaktor is being developed in four distinct phases, each with its own "demo day" deliverable.

## Phase 1: Foundation (Q2 2026)
**Theme**: "Make it real for one external developer."

-   **Testing**: Add a comprehensive test suite to all six stable modules using `kotlin.test`.
-   **Publishing**: Publish engine modules to Maven Central via GitHub Actions.
-   **Documentation**: Launch a Docusaurus site at `docs.reaktor.dev`.
-   **CLI**: Build `reaktor-cli` (`npx create-reaktor-app`) with templates.
-   **Graph Visualizer**: Export running graphs to React Flow for interactive debugging.

## Phase 2: Developer Experience (Q3 2026)
**Theme**: "Make it faster than the alternatives."

-   **Schema DSL**: Generate FlexBuffer schemas, Kotlin data classes, and TypeScript types from a single definition.
-   **GenUI**: Server-driven UI rendering for Compose Multiplatform and React from FlexBuffer schemas.
-   **Hot Reload**: Use `reaktor-ffi` with Hermes to push logic updates without an app store release.
-   **Telemetry Dashboard**: Shipping traces to Grafana/Tempo for per-node latency breakdown.

## Phase 3: Ecosystem (Q4 2026)
**Theme**: "Make it impossible to ignore."

-   **Module Registry**: Launch `reaktor-modules.dev` as a searchable registry.
-   **Shadow/Replay**: Capture and replay application traffic for regression testing without production databases.
-   **Multi-Tenant Infrastructure**: Automated provisioning of k3s tenants with Supabase DBs.
-   **Mesh Networking**: Distributed pub/sub over WebRTC for real-time features.
-   **Open Source Launch**: Release under the AGPL 2.0 license with contributing guidelines.

## Phase 4: Scale (H1 2027)
**Theme**: "Make it the default choice."

-   **Enterprise Features**: SSO/SAML support, audit logging, and SLA monitoring.
-   **IDE Plugin**: IntelliJ/Android Studio plugin for graph visualization and port wiring validation.
-   **Reaktor Cloud**: Managed hosting option on k3s + Cloudflare for teams.
-   **Certification Program**: Online course and certification for Reaktor developers.
-   **Revenue Model**: Transition to a "free engine + paid cloud hosting" royalty model adapted for SaaS.
