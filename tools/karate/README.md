# Reaktor Karate Runner

`run-karate.sh` is the shared server/worker E2E runner, equivalent in shape to
the Maestro wrappers under `reaktor/tools/maestro`.

It runs Karate's standalone JAR so projects can keep API tests under target
folders in `tests/` without creating a Maven or Gradle test source set.

```sh
../reaktor/tools/karate/run-karate.sh --configdir tests/support/karate tests/reaktorServer/karate
../reaktor/tools/karate/run-karate.sh --configdir tests/support/karate --tags @auth tests/reaktorServer/karate
```

With no path, the runner executes every `tests/*/karate` directory. If
`tests/support/karate` exists, it is used as the shared Karate config directory.

Environment knobs:

- `KARATE_VERSION` defaults to `2.0.0`.
- `KARATE_JAR` points at a pre-downloaded standalone JAR.
- `KARATE_CACHE_DIR` defaults to `~/.reaktor/karate`.
- `KARATE_REPORT_DIR` defaults to `tmp/karate-reports/<timestamp>` in the
  calling project.
- `KARATE_LOGBACK_CONFIG` can override the quiet Logback config. The default
  suppresses Karate HTTP request/response logs so bearer tokens are not echoed.

The first run downloads `karate-<version>.jar` from the official Karate Labs
GitHub release into the cache. CI should either cache that directory or provide
`KARATE_JAR`.
