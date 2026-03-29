# reaktor-auth

`reaktor-auth` provides the shared authentication model used across Reaktor products.

## What it includes

- shared auth DTOs and service contracts
- Google and Apple login adapters for Android and iOS
- web auth adapter surface
- JWT verification on the server
- app / user / role / permission / context / session models
- multi-tenant RBAC schema for JVM server deployments

## Current shape

### Client side
- Android: Google login implemented, Apple still depends on the chosen Android flow strategy
- iOS: Google and Apple login implemented
- JS/Web: adapter surface exists, web auth implementation is documented separately

### Server side
- JVM auth server with token verification and profile/user resolution
- typed login responses with explicit failure cases

## Important contracts

- `LoginRequest`
- `LoginResponse`
- `AppService`
- `AuthAdapter`
- `AuthProvider`
- `UserProvider`

## Documentation

- [TECHNICAL_README.md](./TECHNICAL_README.md)
- [WEB_IMPLEMENTATION_SUMMARY.md](./WEB_IMPLEMENTATION_SUMMARY.md)

## Typical usage

Use this module when you need:
- provider login on mobile
- JWT verification on the backend
- shared request/response contracts between app and server
- app-scoped RBAC entities
