# reaktor-cloud

One JVM module with two halves of the Reaktor cloud control plane:

- **Control-plane library** (`dev.shibasis.reaktor.cloud`) — the provider-neutral model
  (`CloudResource`/`CloudProvider`/`CloudToolProvider`/`CloudInventory`/`CloudOperation`/`CloudRun`/`CloudEvent`)
  and JVM tool runners (`ProcessToolRunner`, `DaggerToolProvider`, `PulumiToolProvider`, `ReaktorBrowser`).
  These back the reaktorDesktop **Cloud pane** (see reaktor.build/docs/reaktor-cloud-pane).
- **Observability IaC** (`dev.shibasis.reaktor.cloud.observability`) — a runnable Pulumi program
  that provisions the Grafana folder, datasources (Supabase, Cloudflare→GraphQL, GCP Stackdriver),
  dashboards, and the k3s `k8s-monitoring` Helm release. Config-driven via `Pulumi.<stack>.yaml`.

It is a plain `kotlin("jvm")` + `application` module (not a dependeasy KMP library) because it hosts a
runnable Pulumi app + a build-time codegen step; the cloud control plane is inherently JVM/desktop.

## Grafana SDK — generated at build time (not vendored)
`@pulumiverse/grafana` publishes `sdk: "all,!java"`, so there is no `com.pulumi:grafana` on Maven.
The `genGrafanaSdk` task runs `pulumi package gen-sdk --language java` into `build/grafana-sdk`
(gitignored) and folds it into the main source set. **Requires the `pulumi` CLI on PATH.**

## Build
```sh
# from the reaktor repo root
./gradlew :reaktor-cloud:build
```

## Run the observability stack (operator step — needs real secrets)
Config keys (namespaces: root `reaktor-observability:` + `supabase:`/`cloudflare:`/`gcp:`/`k3s:`):
```sh
pulumi config set --secret reaktor-observability:grafana_service_account_token <token>
pulumi preview   # runtime: java → gradle run, main = dev.shibasis.reaktor.cloud.observability.MainKt
pulumi up
```
