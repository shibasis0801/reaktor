# reaktor-secrets

`reaktor-secrets` is the shared secrets boundary for Reaktor targets.

- Common code owns `SecretRef`, `SecretStore`, `SecretStoreAdapter`, and the
  global `Feature.Secrets` slot.
- JVM targets use `JvmGcpSecretManagerStore`, backed by Google Application
  Default Credentials or explicitly supplied `GoogleCredentials`.
- JS targets use `WebGcpSecretManagerStore` when they have a bearer token
  provider, or a runtime-specific adapter such as the Cloudflare binding store.

GCP Secret Manager is the canonical source of truth. Runtime surfaces that
cannot safely hold privileged GCP credentials should consume short-lived tokens
or synced platform secrets, not bundled service-account files.

## Target model

| Surface | Runtime path |
| --- | --- |
| JVM server / desktop tools / k3s jobs | `JvmGcpSecretManagerStore` with ADC, workload identity, or supplied `GoogleCredentials`. |
| Cloudflare Workers | GCP Secret Manager as source of truth, synced into Worker secret bindings; read with `CloudflareContext.boundSecretStore()`. Workers may also use `WebGcpSecretManagerStore` when a scoped bearer token provider is available. |
| Browser JS tools | `WebGcpSecretManagerStore` only with a scoped token provider; do not ship service-account JSON to browsers. |
| Android / iOS app clients | Do not fetch privileged backend secrets directly. Use a backend token/config endpoint or platform keychain/keystore for user/device secrets. |

## JVM

```kotlin
val store = JvmGcpSecretManagerStore(defaultProjectId = "mehmaan-app")
val value = store.requireString("bestbuds-worker-service-client-secret")
```

BestBuds server bootstrap looks for `BESTBUDS_GOOGLE_CREDENTIALS_SECRET` or
`GOOGLE_SERVICE_ACCOUNT_JSON_SECRET` first. If neither is set, it tries the
default Secret Manager id `bestbuds-google-service-account-json` in project
`mehmaan-app`, then falls back to local developer credential paths.

## Cloudflare Workers

Cloudflare Workers normally receive secrets through bindings. Use GCP Secret
Manager as the source of truth and sync to Worker bindings during deployment,
then read through the Cloudflare adapter:

```kotlin
val store = context.boundSecretStore(defaultProjectId = "mehmaan-app")
val value = store.requireString("BESTBUDS_WORKER_SERVICE_CLIENT_SECRET")
```
