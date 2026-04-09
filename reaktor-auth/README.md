# reaktor-auth

> **Stability: Stable**

`reaktor-auth` provides cross-platform OAuth2/OIDC social login, JWT verification, and role-based access control (RBAC) for Reaktor products.

## What it includes

- Google and Apple login adapters for Android and iOS
- Web auth adapter surface (Google Identity Services, Sign in with Apple JS)
- JWT verification on the server (Spring Security)
- Shared auth DTOs and service contracts
- App / user / role / permission / context / session models
- Multi-tenant RBAC schema for JVM server deployments (PostgreSQL + Exposed ORM)
- Token caching and refresh via `AuthObjectStore`
- Compose UI components: `LoginButtons`, `GoogleIcon`, `AppleIcon`

## Platforms

### Client side
- **Android**: Google login via Credential Manager API. Apple login planned.
- **iOS**: Google login via GoogleSignIn pod (8.0.0). Apple login via AuthenticationServices.
- **JS/Web**: Adapter surface exists. Google Identity Services and Apple Sign-In JS interop defined.

### Server side
- **JVM**: Spring Boot auth server with JWT verification, profile/user resolution, token minting.
- Full RBAC schema: Users, Apps, Roles, Permissions, Sessions, Contexts (Exposed ORM + PostgreSQL).

## Key types

### Auth flow

| Type | Purpose |
|---|---|
| `AuthAdapter<Controller>` | Main auth bridge. Manages providers, login flow, token caching. |
| `AuthProvider<Adapter, User>` | Abstract provider (Google, Apple). Handles platform-specific login. |
| `AuthProviderUser` | User data from provider: `idToken`, `emailId`, `givenName`, `familyName` |
| `GoogleUser` / `AppleUser` | Provider-specific user data classes |

### Service contracts

| Type | Purpose |
|---|---|
| `AuthService` | Service with `login`, `mintPat`, `verifyPat` handlers |
| `LoginRequest` | `idToken`, `appId`, `provider`, name fields, profile data |
| `LoginResponse` | Sealed: `Success` (user, tokens) or typed failures |
| `AppService` | App management: `getAll`, `getApp` |

### LoginResponse failures

`InvalidIdToken`, `InvalidAppId`, `UnsupportedUserProvider`, `RequiresUserName`, `RequiresUserProfile`, `AppLoginFailure`, `ServerError`

### RBAC (server)

`User`, `Role`, `Permission`, `Session`, `Context` - Exposed ORM entities with `Auditable` mixin for creation/modification tracking.

## Graph integration

- `AuthNode` - Integrates auth adapter with graph lifecycle
- `SecuredPort` - Wraps ports requiring authentication
- Feature slot: `Feature.Auth`

## Documentation

- [TECHNICAL_README.md](./TECHNICAL_README.md) - Implementation details
- [WEB_IMPLEMENTATION_SUMMARY.md](./WEB_IMPLEMENTATION_SUMMARY.md) - Web auth specifics

## Typical usage

Use this module when you need:
- Provider login on mobile (Google, Apple)
- JWT verification on the backend
- Shared request/response contracts between app and server
- App-scoped RBAC entities
