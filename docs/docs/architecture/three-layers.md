# Strategic Architecture: The Three Layers

To evolve from a framework into a platform, Reaktor is architected into three distinct layers, each with its own target users, quality standards, and release cycle.

## Layer 1: The Engine (reaktor-engine)

This is the foundation—equivalent to Unreal's core runtime. It must be rock-solid and stable.

| Module | Purpose |
| --- | --- |
| **reaktor-core** | Adapters, feature registry, capabilities, and cross-platform primitives. |
| **reaktor-graph** | The graph runtime, node lifecycle, DI scoping, and the visitor pattern. |
| **reaktor-graph-port** | Typed ports, edges, and automatic wiring of dependencies. |
| **reaktor-io** | Route patterns, request/response shapes, and transport abstraction. |
| **reaktor-db** | ObjectStore, cache policies, and offline-first repository patterns. |
| **reaktor-flexbuffer** | Zero-copy serialization, Schema DSL, and content negotiation. |

**Quality Bar**: 100% API documentation, >80% test coverage, semantic versioning, and published to Maven Central.

## Layer 2: Platform Services (reaktor-platform)

These are the "batteries included" modules—equivalent to built-in physics or multiplayer systems.

- **reaktor-auth**: OAuth2/OIDC, JWT, and RBAC support.
- **reaktor-cloudflare**: Adapters for Cloudflare Workers, Durable Objects, D1, and R2.
- **reaktor-google**: Pub/Sub and Cloud Functions integration.
- **reaktor-telemetry**: OpenTelemetry, Firebase Analytics, and Crashlytics.
- **reaktor-work**: Background task orchestration.
- **reaktor-media**: Camera, gallery, and image pipelines.
- **reaktor-notification**: Push notification delivery.

**Quality Bar**: Documented API surface and integration tests.

## Layer 3: The Application Layer (reaktor-apps)

These are the flagship products that prove the engine's capabilities and generate revenue.

- **Bestbuds**: A social and messaging application. Proves real-time features, auth, and offline-first capabilities.
- **Manna**: An AI-powered upskilling platform. Proves knowledge graphs and AI integration.
- **Nexergy**: A biomass pellet e-commerce platform. Proves transactional flows and payments.
- **Consulting Projects**: Client applications that prove enterprise readiness across diverse use cases.
