# Reaktor k6 Runner

`tools/k6/run-k6.sh` is the shared load-test runner for Reaktor projects.
It mirrors the Maestro and Karate layout: application scripts live under
target folders such as `tests/reaktorServer/k6`, package scripts invoke this
runner, and the `reaktor` CLI exposes the resulting `k6:<target>:*` family
automatically.

```sh
../reaktor/tools/k6/run-k6.sh tests/reaktorServer/k6
../reaktor/tools/k6/run-k6.sh tests/reaktorServer/k6/auth-smoke.js
K6_RPS=10 K6_DURATION=5m ../reaktor/tools/k6/run-k6.sh tests/reaktorServer/k6/auth-load.js
```

With no path, the runner executes every `tests/*/k6` directory.

The runner requires a local `k6` binary. Set `K6_BIN` if it is not on `PATH`.
Summary JSON files are written to `tmp/k6-results/<timestamp>` unless
`K6_OUTPUT_DIR` is set. Set `K6_OUT` to stream metrics to an external backend,
for example Prometheus remote write.
