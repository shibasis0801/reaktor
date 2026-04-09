# reaktor-io

> **Stability: Stable**

`reaktor-io` provides the shared transport-level utilities used by Reaktor services and routing.

## Responsibilities

- Route pattern parsing and parameter filling
- Shared `Request` / `Response` shapes
- Environment propagation (PROD, STAGE, DEV)
- Typed service request handlers
- Client-side HTTP (Ktor) and WebSocket helpers
- Cross-platform file I/O and compression

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

## Key types

| Type | Purpose |
|---|---|
| `RoutePattern` | URL pattern parsing with regex and parameter extraction (e.g., `/{id}/something/{members}`) |
| `Request` / `Response` | Base HTTP shapes with serialization support |
| `Environment` | Runtime environment switching |
| `GetHandler`, `PostHandler` | Typed route handlers |
| `HttpClient` | Ktor-based HTTP client with middleware (logging, WebSocket, content negotiation) |
| `PartySocket` | WebSocket abstraction with reconnection strategies |
| `WebSocket` | Cross-platform WebSocket with Listener, Sender, Receiver |
| `FileAdapter` | Cross-platform file I/O abstraction |
| `Compressor` | Platform-specific compression (native on Android/Darwin/JVM) |
| `ObjectSerializer` | Serialization interface layer |

## How it fits

`reaktor-io` is the low-level transport utility layer.
`reaktor-graph` uses it for its service model.
Platform modules such as `reaktor-cloudflare` adapt those contracts to actual runtimes.

## Dependencies

- `reaktor-core`
- `kotlinx-io-core` - I/O primitives
- Ktor client with plugins (WebSocket, logging, content negotiation)
- BuildKonfig - Build-time configuration
