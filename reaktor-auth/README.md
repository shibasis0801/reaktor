# reaktor-auth

> Stability: phase-2 auth system

`reaktor-auth` is Reaktor's graph-native auth engine. It normalizes external
provider login into Reaktor principals, issues short-lived Reaktor access
tokens, rotates refresh-token families, exchanges PATs, authenticates service
accounts, and projects everything through graph capability ports.

The module intentionally does not support the old `users` / `user_role` auth
model as a runtime API. Rebuild Supabase from a full `heimdall` + `public` data
dump, run the phase-2 schema in
[`heimdall.sql`](src/jvmMain/kotlin/dev/shibasis/reaktor/auth/heimdall.sql),
then restore compatible phase-2 auth data.

## What It Owns

- User login through external providers.
- Global identity, provider account, principal, membership, tenant/context, and
  RBAC records.
- `AuthContext` and `AuthRequirement` as the common authorization model.
- ES256 access-token minting and verification through JWKS.
- Session creation, rotating refresh tokens, logout, and logout-all.
- PAT mint/verify/exchange with hash-at-rest and audience binding.
- Service-account `client_credentials` tokens.
- Delegated token exchange with user subject and service actor.
- Spring boundary adapters.
- Graph capability ports for consumers that want direct composition.

## Current Runtime Shape

```text
External credential
  -> AuthRuntimeGraph
  -> AuthContext
  -> short-lived Reaktor access token
  -> Spring / Worker / graph / actor boundary
  -> AuthRequirement / LocalAuthorizer
  -> domain handler
```

Spring and HTTP are adapters. The engine is the graph:

```kotlin
val runtime = AuthRuntimeGraph.create(
    adapter = exposedAdapter,
    config = AuthRuntimeConfig(
        userAuthenticationProviders = providers,
        ecJwkJson = ecJwkJson,
        ecJwkPrevJson = ecJwkPrevJson,
    ),
)
```

or:

```kotlin
val runtime = graph.installAuthRuntime(exposedAdapter, config)
```

## Common API

The 80% path stays small:

- Client calls `AuthAdapter.login(...)`.
- Server verifies the external provider credential.
- Auth graph resolves app, identity, principal, membership, roles, and
  permissions.
- Auth graph returns `AuthContextSnapshot` and `TokenSet`.
- Services and Workers verify Reaktor access tokens locally.
- Handlers authorize with `AuthRequirement`.

Lower-level graph consumers can use:

- `AuthLogin`
- `AuthPat`
- `AuthTokenGrants`
- `AuthSessions`
- `AuthHttpService`
- `AuthAppService`
- `AuthAppCatalog`
- `AuthPrincipalDirectory`
- `AuthPersonalTokens`
- `AuthSessionLifecycle`
- `AuthServiceAccounts`

## Runtime Graph Nodes

| Node | Provides |
| --- | --- |
| `AuthRepositoryNode` | app catalog, principal directory |
| `AuthJwtNode` | signing keys, JWT minter/verifier, external identity verifier |
| `AuthPersonalTokenNode` | PAT storage, verify, revoke, PAT token mint helper |
| `AuthSessionLifecycleNode` | session creation, refresh rotation, logout |
| `AuthServiceAccountNode` | service-account auth and token minting |
| `LoginInteractorNode` | external login -> session + token set |
| `AuthPatNode` | PAT mint/verify |
| `AuthTokenGrantNode` | `pat`, `client_credentials`, token exchange |
| `AuthSessionNode` | refresh, logout, me, logout-all |
| `AuthHttpServiceNode` | `AuthService` route projection |
| `AuthAppServiceNode` | `AppService` route projection |

The graph registers only external runtime dependencies (`ExposedAdapter` and
`AuthRuntimeConfig`). Nodes consume dependencies and ports from the graph rather
than receiving ad hoc constructor wiring.

## Token Families

- Access token: ES256 JWT, `typ=at+jwt`, verified through JWKS.
- Refresh token: `rkr_` opaque token, one-time-use, SHA-256 hash at rest,
  family reuse detection.
- PAT: `rkt_` opaque token, offline checksum, SHA-256 hash at rest, optional
  expiry, scopes, app/audience binding. Legacy `rak_` tokens still verify.
- Service account: `client_credentials` with `PRIVATE_KEY_JWT` or secret
  bootstrap.
- Delegated token: user subject plus service actor in `act`.

## HTTP Routes

When mounted under `/auth`:

- `POST /auth/sign-in`
- `POST /auth/token`
- `POST /auth/pat/mint`
- `POST /auth/pat/verify`
- `POST /auth/pat/exchange`
- `POST /auth/session/refresh`
- `POST /auth/session/logout`
- `POST /auth/session/me`
- `POST /auth/session/logout-all`
- `GET /.well-known/jwks.json`

`/auth/token` supports `pat`, `personal_access_token`,
`client_credentials`, and `urn:ietf:params:oauth:grant-type:token-exchange`.

## Spring Boundary

`AuthSpringConfiguration` creates the runtime with `SpringDependencyAdapter` and
exposes:

- `AuthServer`
- `AppServer`
- `ReaktorSecurityWebFilter`
- `JwksController`

`DefaultSecurityConfig` installs the Reaktor filter at the authentication
position. The filter verifies bearer tokens locally, converts claims to
`AuthContext`, writes the context to the exchange attributes, and exposes Spring
authorities for framework integration. Fine-grained authorization still belongs
in `AuthRequirement` plus `LocalAuthorizer`.

## Schema

Run the schema on an empty/rebuilt database:

```bash
psql "$SUPABASE_DB_URL" -f src/jvmMain/kotlin/dev/shibasis/reaktor/auth/heimdall.sql
```

The schema creates:

- `app`
- `identity`
- `provider_account`
- `principal`
- `tenant`
- `context`
- `membership`
- `role`, `permission`, `role_permissions`, `principal_role`
- `auth_provider_client`
- `session`
- `refresh_token`
- `service_account`
- `personal_access_token`
- `signing_key`

## Implemented vs Open

Implemented:

- Graph runtime composition.
- ES256/JWKS access tokens.
- Rotating refresh tokens and reuse detection.
- PAT lifecycle and exchange.
- Service-account `client_credentials`.
- Delegated token exchange token shape.
- Spring adapter.
- Worker-facing JWT verification in BestBuds consumers.
- Target-owned Karate/k6 test layout.

Open:

- Supabase email/password and magic link provider adapter.
- Browser Authorization Code + PKCE.
- BFF `__Host-` cookie model.
- DPoP.
- Persisted `may_act` delegation policy.
- ReBAC object tuples.
- First-class auth event/audit table.

## Validation

```bash
./gradlew :reaktor-auth:check --console=plain
```

BestBuds live auth gates:

```bash
npm run test:auth:karate:smoke
KARATE_USE_SERVER_APPLICATION_DB=1 npm run test:auth:karate:live
```
