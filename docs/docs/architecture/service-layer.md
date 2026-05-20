# The Service Layer: A gRPC Superset

Reaktor's Service layer is a transport-agnostic, typed RPC system that surpasses gRPC in flexibility. It defines six transport modes that all share the same typed `Request/Response` contract.

## Transport Modes

| Transport | Wire Protocol | Use Case | Platform Support |
| --- | --- | --- | --- |
| **HTTP** | HTTP/1.1, HTTP/2, HTTP/3 | Client-to-server RPC, REST APIs, public endpoints. | All (Android, iOS, JVM, JS, Workers). |
| **LOCAL** | In-process function call | Service-to-service within the same graph. Mock implementations. | All platforms. |
| **PEER** | Reaktor Mesh DataChannel | P2P RPC between two mesh peers. | All platforms with Mesh support. |
| **PUBSUB** | GCP Pub/Sub message | Async event-driven communication and cross-service notifications. | JVM, Workers. |
| **QUEUE** | Task Queue (GCP Tasks, Cloudflare Queues) | Background job processing with guaranteed delivery. | Workers, JVM. |
| **WORKFLOW** | Durable workflow (Cloudflare Workflows) | Multi-step, long-running processes with checkpointing and retry. | Workers, JVM. |

## The Service Contract

Every service operation is a typed pair: `Request → Response`. A single Service class defines both the client and server sides of every operation.

- **Server Definition**: `Service.server(PostHandler, "/users") { request -> ... }` defines the handler logic.
- **Client Definition**: `Service.client(PostHandler, "/users")` auto-generates the HTTP client call.
- **Transport Transparency**: You can switch from `HTTP` to `LOCAL` for testing, or to `PEER` for P2P, by changing only the `ServiceEndpoint`.

## Interceptor / Middleware System

Services support a composable interceptor chain that runs on both client and server execution phases.

- **Authentication**: Validates JWT tokens on the server and attaches them to the client.
- **Caching**: Checks `ObjectStore` before invoking a handler. Cache hits return directly.
- **Telemetry**: Wraps every invocation in an OpenTelemetry span for automated tracing.
- **Rate Limiting**: Tracks and limits requests per principal from `reaktor-auth`.
- **Validation**: Automatically validates incoming requests against Schema DSL rules.
