# reaktor-cloudflare

`reaktor-cloudflare` is the Kotlin/JS runtime layer for Cloudflare deployments.

## What it covers

- Workers
- D1
- R2
- Durable Objects
- service bindings
- Vector bindings
- secrets
- Hono integration
- PartyKit integration

## Design goals

- typed access to Cloudflare bindings from Kotlin
- request-aware binding lookup instead of raw `dynamic`
- shared service contracts mounted directly on Cloudflare handlers
- escape hatches for raw responses, multipart handling, and websocket/realtime surfaces

## Important types

- `CloudflareContext`
- `Binding<T>` and concrete binding types
- `CloudflareWorkerRequest`
- `CloudflareResponse`
- `WorkerService`
- `CloudflareWorker`
- `CloudflareDurableObject`
- `PartyKitServer`
- `PartyKitRoom`

## Current use in production

BestBuds uses this module for:
- social worker
- messaging worker
- media worker
- config worker
- realtime PartyKit chat

## Typical usage

Use this module when you want to:
- implement a worker in Kotlin instead of TypeScript
- resolve bindings safely from request context
- mount Reaktor services into Cloudflare routes
- access PartyKit from Kotlin with a typed wrapper surface
