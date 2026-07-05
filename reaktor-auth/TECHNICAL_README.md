# reaktor-auth Technical Guide

`reaktor-auth` is built around one runtime primitive: `AuthContext`.

Provider login, refresh rotation, PAT exchange, service credentials, token
exchange, Spring Security, Worker verification, and graph ports all converge on
that type. The public API stays compact, while graph-aware consumers can drop
down to lower-level capabilities.

## Layering

```text
commonMain/kernel
  AuthContext, AuthRequirement, LocalAuthorizer

commonMain/graph
  AuthNode, AuthPolicyNode, secured ports

jvmMain/runtime/ports
  low-level capability contracts

jvmMain/runtime/nodes
  graph nodes that implement and compose capabilities

jvmMain/spring
  framework boundary adapter

common/js/android/ios clients
  platform login facades and UI
```

## Kernel

`src/commonMain/.../kernel/AuthKernel.kt`

Important types:

- `AuthContext`: principal, identity, app, tenant, context, session, token,
  credential, issuer, audience, scopes, roles, permissions, method, actor, and
  delegation.
- `AuthRequirement`: audience/app/tenant constraints, `anyOf`/`allOf`, scopes,
  permissions, roles, resource, principal kinds, and delegation allowance.
- `LocalAuthorizer`: pure evaluator returning `AuthDecision.Allow` or
  `AuthDecision.Deny`.

The kernel has no database, Spring, Worker, or platform-login dependency.

## Shared DTOs

`src/commonMain/.../RoleBasedAuth.kt` mirrors the phase-2 schema:

- `App`
- `AuthIdentity`
- `AuthProviderAccount`
- `AuthPrincipal`
- `Membership`
- `Role`
- `Permission`
- `PrincipalRole`
- `Session`
- `RefreshToken`
- `PersonalAccessToken`
- `ServiceAccount`
- `SigningKeyRecord`

`AuthContextSnapshot` is the serialized boundary for login responses and
object-store caching.

## Graph Layer

`src/commonMain/.../graph/AuthNode.kt`

- `AuthNode`: stateful `AuthContextProvider` + `AuthContextSink`.
- `AuthPolicyNode`: exposes `AuthPolicy`.
- `AuthGraph.install(graph)`: attaches both nodes to a caller-owned graph.

`SecuredPort.kt` adds `AuthRequirement`-aware provider and consumer ports. These
ports authorize against an `AuthContext` or `AuthContextProvider`; there is no
separate user/session adapter layer.

## JVM Runtime Graph

`src/jvmMain/.../runtime/AuthRuntimeGraph.kt`

`AuthRuntimeGraph` is a real `Graph`. It owns auth runtime composition and can
also be mounted under another graph with `Graph.installAuthRuntime(...)`.

External inputs:

- `ExposedAdapter`, registered as `reaktorAuth.exposedAdapter`.
- `AuthRuntimeConfig`, registered as `reaktorAuth.config`.

`AuthRuntimeConfig` contains:

- `userAuthenticationProviders`
- `ecJwkJson`
- `ecJwkPrevJson`

The runtime calls `autoWire()` and `requireFullyWired()`, so missing capability
edges fail early.

## Runtime Ports

`src/jvmMain/.../runtime/ports/AuthRuntimePorts.kt`

Low-level ports:

- `AuthAppCatalog`
- `AuthPrincipalDirectory`
- `AuthExternalIdentityVerifier`
- `AuthPersonalTokens`
- `AuthSessionLifecycle`
- `AuthServiceAccounts`

High-level capabilities:

- `AuthLogin`
- `AuthPat`
- `AuthTokenGrants`
- `AuthSessions`
- `AuthHttpService`
- `AuthAppService`

This split is intentional. The high-level ports cover the common API, and the
low-level ports keep specialized composition possible without adding top-level
one-shot APIs.

## Runtime Nodes

| Node | Responsibility |
| --- | --- |
| `AuthRepositoryNode` | Database-backed app catalog and principal directory. Resolves external login into identity/provider/principal/membership plus roles/permissions. |
| `AuthJwtNode` | Builds signing keys, `JwtMinter`, `JwtVerifier`, and external provider verification. |
| `AuthPersonalTokenNode` | Wraps PAT creation, verification, revocation, and PAT access-token minting helpers. |
| `AuthSessionLifecycleNode` | Wraps session creation, refresh rotation, family reuse detection, logout, and logout-all. |
| `AuthServiceAccountNode` | Wraps service-account authentication, audience checks, scope down-scoping, and service token minting. |
| `LoginInteractorNode` | Composes app catalog, external verifier, principal directory, session lifecycle, and minter into login. |
| `AuthPatNode` | Composes PAT mint/verify. |
| `AuthTokenGrantNode` | Implements `/auth/token` grants: PAT, `client_credentials`, and token exchange. |
| `AuthSessionNode` | Implements refresh, logout, me, and logout-all. |
| `AuthHttpServiceNode` | Projects graph capabilities into `AuthService` handlers. |
| `AuthAppServiceNode` | Projects app catalog into `AppService` handlers. |

## Token and Crypto Details

Access tokens:

- ES256.
- `typ=at+jwt`.
- `kid` header.
- `iss=https://api.reaktor.build/auth`.
- `aud` pinned by resource servers when required.
- `sub` is the principal id.
- `principal_type` is `user`, `service`, `agent`, or `actor`.
- `credential_type` is `access_token`, `client_credentials`, `pat`, or
  `delegation`.
- `scp` and `scopes` are both emitted.
- `roles` and `permissions` can be emitted.
- `sid` is set for session-backed user tokens.
- `identity_id`, `tid`, and `ctx` are set when known.
- `act.sub` is set for delegated tokens.

`JwtVerifier` has two Reaktor verification paths:

- `verifyReaktorToken(token, audiences)`: signature, issuer, expiry, and
  audience validation.
- `verifyReaktorSignature(token)`: signature, issuer, and expiry only. Used by
  the Spring authentication filter; route authorization validates audience and
  requirements later.

External provider verification uses configured `UserAuthenticationProvider`
values. Provider-client rows exist in the schema, but the live verifier is still
runtime-config driven.

## Sessions

`SessionRefreshService` owns refresh lifecycle:

- `createSession(...)` creates a `session` row and first `refresh_token` row.
- `rotate(rawRefresh)` marks the old token used and inserts the successor.
- Reuse of an already-used token revokes the family.
- `revokeByRefreshToken(...)` logs out one family.
- `revokeAllForPrincipal(...)` logs out every active session for a principal.

Refresh tokens are `rkr_` opaque values with SHA-256 hashes stored in the
database.

## PATs

`AuthPersonalTokenNode` and `AuthPatNode` own PATs:

- New tokens are `rkt_` values.
- The body is high entropy and has an offline CRC32 checksum.
- The database stores only SHA-256 hash and metadata.
- Tokens can be scoped, app-bound, audience-bound, expiring, and revoked.
- Exchange mints a short-lived access JWT.

`/auth/pat/mint` requires a bootstrap token or an existing PAT with
`auth:pat:mint`, `auth:*`, or `*`.

## Service Accounts and Delegation

`ServiceAccountService` supports:

- `PRIVATE_KEY_JWT` client authentication.
- `SECRET` authentication for bootstrap.
- Audience allowlist.
- Scope intersection.
- `client_credentials` service tokens.

`AuthTokenGrantNode` supports RFC 8693 token exchange:

- Service account authenticates as actor.
- User access token is the `subjectToken`.
- Minted token uses the user as `sub`.
- Actor service principal is stored in `act.sub`.

Persisted `may_act` policy is not implemented yet.

## Spring Boundary

`AuthSpringConfiguration` creates the graph through `SpringDependencyAdapter` and
exposes framework beans:

- `AuthServer`
- `AppServer`
- `ReaktorSecurityWebFilter`
- `JwksController`

`DefaultSecurityConfig`:

- Installs the Reaktor filter at `SecurityWebFiltersOrder.AUTHENTICATION`.
- Publicly permits status, auth credential-exchange routes, JWKS, graph gateway,
  and health probes.
- Requires authentication for everything else.
- Disables CSRF and uses `NoOpServerSecurityContextRepository`.

The filter verifies tokens locally and puts `AuthContext` at
`ReaktorSecurityWebFilter.AUTH_CONTEXT_ATTR`.

## HTTP Handlers

`AuthHttpServiceNode` routes:

- `/sign-in`
- `/token`
- `/pat/mint`
- `/pat/verify`
- `/pat/exchange`
- `/session/refresh`
- `/session/logout`
- `/session/me`
- `/session/logout-all`

When mounted in the app server, these appear under `/auth`.

`AuthServer` only delegates to graph-produced `AuthService`; it does not own
business logic.

## Schema Source of Truth

`src/jvmMain/kotlin/dev/shibasis/reaktor/auth/heimdall.sql`

The schema creates the phase-2 auth model. It is not a compatibility migration
for the old user table. Dump and restore data separately.

## Tests

Module tests cover:

- kernel authorization.
- graph install and secured ports.
- runtime graph wiring and handler projection.
- login resolution and token set creation.
- JWT round trips, audience pinning, expiry, tamper detection, and algorithm
  pinning.
- refresh rotation and reuse detection.
- PAT format, verify, revoke, exchange.
- service-account secret/private-key auth.
- delegated token exchange.
- handler-level auth server behavior.
- architecture guardrails for graph decomposition and Spring adapter use.

Run:

```bash
./gradlew :reaktor-auth:check --console=plain
```

BestBuds live gates:

```bash
npm run test:auth:karate:smoke
KARATE_USE_SERVER_APPLICATION_DB=1 npm run test:auth:karate:live
```

## Open Technical Work

- Supabase email/password and magic-link provider.
- Browser Authorization Code + PKCE and BFF cookies.
- DPoP.
- Persisted `may_act` delegation policy.
- ReBAC object tuple storage and checks.
- Auth event/audit tables.
- Session device/IP/user-agent metadata.
- Removal of static Worker service-token fallback.
