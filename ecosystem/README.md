# Reaktor ecosystem

Measure is pinned as the `ecosystem/measure` Git submodule at `v0.13.0`.
Iggy is pinned to 0.8.0, which matches this server's queue configuration.
The upstream checkout stays unchanged; Reaktor's launcher and Compose overrides
live beside it.

## Start

Reaktor Desktop starts Measure in a background thread when its workspace can
locate this folder. Set `REAKTOR_ECOSYSTEM_ROOT` to this folder for a workspace
outside Reaktor's parent directory. Docker Desktop must already be running.
The first launch downloads images and builds the services sequentially to limit
memory pressure. Later launches reuse them.

```sh
git submodule update --init ecosystem/measure
./ecosystem/measure.sh up
./ecosystem/measure.sh status
./ecosystem/measure.sh connect
./ecosystem/measure.sh stop
```

`stop` preserves recordings and database volumes. Closing Reaktor also preserves
Measure's services so an app can finish uploading its session.

| Surface | Local URL |
| --- | --- |
| DevTools API | http://127.0.0.1:47180 |
| SDK ingestion | http://127.0.0.1:47185 |
| Measure dashboard | http://127.0.0.1:47130 |
| Session attachments | http://127.0.0.1:47190 |

Only these ports bind to loopback. Databases and the queue have no host ports.
The launcher uses an isolated Docker configuration for public pulls, resolves
the active Docker host, and builds native images even when the shell sets
`DOCKER_DEFAULT_PLATFORM`. It leaves the global Docker settings unchanged.

## DevTools

Open **DevTools → Measure**, then select **Local Android** or **Local iOS**.
Those buttons provision or renew a 30-minute dashboard access token for a local
operator and select the platform's app. Repeat the selection if the token expires.
They work independently of device attachment. Health, sessions, crash/ANR groups
and traces support time and version filters; lists and error instances are paged.
Health and selected traces export the standard Reaktor performance report.

The provisioned operator is specific to this local installation. The optional
web dashboard retains upstream OAuth sign-in; configure its Google/GitHub OAuth
values in `.state/measure.env` when using that dashboard. Rebuild after changing
its public build settings. The pane can use its own local operator immediately.

`.state/` is ignored. It holds the persistent installation identity, randomized
service passwords, dashboard connection and SDK ingestion keys. Environment and
connection files are mode 0600; the directory is mode 0700. Do not commit them.
The dashboard token and SDK key are distinct credentials. Startup diagnostics are
in `.state/measure-server.log` and `.state/reaktor-startup.log`.

## Record from apps

BestBuds Android debug builds read the local Android ingestion key from
`.state/connection.json`. Use `REAKTOR_MEASURE_CONNECTION` to select another
connection file. Enable the existing `bestbuds.devTools` flag to initialize
full capture for local debugging. Release manifests do not include these credentials.

For an attached Android device, run `adb reverse tcp:47185 tcp:47185`; the default
ingestion URL is then the device's loopback address. For an emulator without
reverse forwarding, use `-Preaktor.measure.ingestUrl=http://10.0.2.2:47185`.
Rebuild the debug APK after provisioning its app key.

The iOS host links `measure-sh` 0.14.1 and uses full capture for opted-in debug launches. In a debug launch, pass
`REAKTOR_MEASURE_IOS_KEY` from the local iOS app's `api_key`, and optionally
`REAKTOR_MEASURE_INGEST_URL`. The simulator's default URL is loopback; a physical
phone needs a reachable HTTPS ingestion endpoint or a forwarded connection.
Keep keys in ignored launch settings. Reaktor's service interceptor records
service operation spans when DevTools are enabled; screen and custom traces can
use `ReaktorMeasure` with graph scope.

## Verify

```sh
./ecosystem/measure.sh config
python3 -m unittest discover -s ecosystem -p '*_test.py'
./gradlew :reaktor-performance:jvmTest :reaktor-devtools:jvmTest
```

Upstream: https://github.com/measure-sh/measure
