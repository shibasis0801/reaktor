# reaktor-io

`reaktor-io` provides the shared transport-level utilities used by Reaktor services and routing.

## Responsibilities

- route pattern parsing and filling
- shared `Request` / `Response` shapes
- environment propagation
- typed service request handlers
- client-side HTTP and websocket helpers

## Important concepts

- `RoutePattern`
- `Request`
- `Response`
- `Environment`
- typed handlers such as `GetHandler` and `PostHandler`

## How it fits

`reaktor-io` is the low-level transport utility layer.
`reaktor-graph` uses it for its service model.
Platform modules such as `reaktor-cloudflare` adapt those contracts to actual runtimes.
