# reaktor-cloudflare

> **Stability: Experimental** - Production-integrated in BestBuds but API may evolve.

`reaktor-cloudflare` is the Kotlin/JS runtime layer for Cloudflare deployments. It enables writing Cloudflare Workers in Kotlin instead of TypeScript while maintaining strong typing.

## What it covers

- Workers with Hono routing
- D1 (SQLite database) with query builders and typed row mapping
- R2 (object storage) with streaming and JSON support
- Durable Objects for persistent actor-like computation
- Service bindings for worker-to-worker calls
- Vectorize for embedding/vector database queries
- Secrets management via bindings
- PartyKit integration for real-time WebSocket rooms (with hibernation support)

## Platforms

JavaScript/Kotlin/JS only (Cloudflare Workers runtime)

## Key types

| Type | Purpose |
|---|---|
| `CloudflareContext` | Central access point for all bindings in request context |
| `CloudflareWorkerRequest` | Typed wrapper for incoming requests |
| `CloudflareResponse` | Response with typed access |
| `CloudflareWorker` | Worker definition |
| `CloudflareEnv` | Marker interface for environment bindings |
| `WorkerService` | Service-to-service calls via bindings |
| `Binding<T>` | Typed binding resolution |
| `D1Database` / `D1Mutation` | SQLite database interaction |
| `R2Bucket` / `R2Object` | Object storage (files) |
| `DurableObjectNamespace` / `DurableObjectStub` / `DurableObjectStorage` | Persistent state |
| `CloudflareDurableObject` | Durable Object definition |
| `VectorIndex` / `VectorizeVector` / `VectorizeMatches` | Vector database queries |
| `PartyKitServer` / `PartyKitRoom` / `PartyKitConnection` | Real-time WebSocket support |

## Design goals

- Typed access to Cloudflare bindings from Kotlin
- Request-aware binding lookup instead of raw `dynamic`
- Shared service contracts mounted directly on Cloudflare handlers
- Escape hatches for raw responses, multipart handling, and WebSocket surfaces

## Current use in production

BestBuds uses this module for:
- Social worker
- Messaging worker
- Media worker
- Config worker
- Realtime PartyKit chat

## Dependencies

- `reaktor-core`, `reaktor-graph`, `reaktor-io`
- NPM: `hono` (4.9.8), `partykit` (0.0.115), `postgres` (3.4.5)
