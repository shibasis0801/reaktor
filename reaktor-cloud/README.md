# reaktor-cloud

Dependeasy configures this module as a Kotlin Multiplatform library with JVM,
Android and Apple targets. Its JVM code contains:

- `CloudToolProvider`, operation/plan/event models and `ProcessToolRunner` for
  supervised process execution;
- `PulumiToolProvider` for explicit-stack preview, refresh and apply operations,
  with approval bound to the reviewed command and program contents;
- the observability Pulumi program under `cloud/observability`.

Build operations come from the Dependeasy model. The former per-product Dagger
provider and `.dagger` module are removed. Local container CI uses the shared
module at `dependeasy/dagger`; its entry is `run --target portableChecks`.

## Build and check

From the Reaktor repository root:

```sh
./gradlew :reaktor-cloud:compileKotlinJvm --console=plain
./gradlew :reaktor-cloud:cloudToolE2e --console=plain
```

The existing process-boundary check executes a local shell fixture. It checks
single process ownership, exact Pulumi approval, and source changes before process
admission; it does not access a cloud account.

## Generated Grafana SDK

`genGrafanaSdk` runs the checksummed Pulumi CLI installed by Dependeasy, with its
version from `ToolchainVersions`. It writes `build/dependeasy/grafana-sdk`.
`compileGrafanaSdk` and `grafanaSdkJar` compile and package that output, which is a
JVM dependency of this module. Generated SDK files are not edited or vendored.

## Observability ownership

The Pulumi entry point is `dev.shibasis.reaktor.cloud.observability.MainKt`, exposed
as `:reaktor-cloud:run`. It currently has no checked-in stack configuration. Live
observability is managed by BestBuds' `cloud/k3s/observability` scripts; delivery WP25
covers adopting those existing resources into Pulumi. The program's presence is
not evidence of a completed live migration.

The runtime `PulumiToolProvider` still defaults to `pulumi` on PATH. Wiring it to
the pinned toolchain is a remaining WP19 item; SDK generation already uses the
pinned distribution.
