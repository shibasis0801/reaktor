# Private JVM output-cache pilot

The worker runs one [Silo 0.2.0](https://github.com/chrisjenx/silo/tree/v0.2.0)
container pinned to its ARM64 image digest. Upstream labels Silo **alpha**; this
deployment is a bounded pilot, not a production availability dependency. Gradle
can continue without the cache. The service uses one CPU, 768 MiB memory, a 4 GiB
retention cap and fourteen-day eviction. It binds worker loopback port 18085;
the existing SSH port tunnel makes that same endpoint available on this Mac.

Provision credentials once, then start through the workspace's Docker transport:

```sh
python3 ~/dev/tools/setup-build-cache.py
cd ~/dev/reaktor
~/dev/tools/remote-dev docker compose -f tools/build-cache/compose.yaml up -d
```

Passwords remain in `~/dev/.remote-dev/build-cache/client.json` (mode 0600).
Only bcrypt hashes are mounted into the container. The reader and writer have
separate credentials; anonymous access is disabled. Worker process environments
travel over SSH stdin, rather than command arguments. No credential is committed.

Cache access is **off by default**. `REMOTE_DEV_CACHE=read` injects the reader;
`REMOTE_DEV_CACHE=write` is accepted only in a verified snapshot execution slot.
`CI=true` grants no authority. The injected policy currently admits only JVM main
compilation and jars; UI, device, service tests, JS and native tasks do not use
the pilot. Their relocation/eligibility checks remain separate work.

```sh
REMOTE_DEV_CACHE=read ~/dev/tools/remote-dev graph-run bestbuds portableChecks -- -Pbestbuds.devTools=false
REMOTE_DEV_CACHE=write ~/dev/tools/remote-dev graph-run bestbuds portableChecks -- -Pbestbuds.devTools=false
```

Keep host-local caches and incremental state. `REMOTE_DEV_CACHE_LOCAL=false` is
an explicit experiment switch for proving HTTP restores; it does not delete any
cache. A failed upload or unavailable cache must never be interpreted as a test
pass. Test results and build artifacts have their own receipts independently of
Gradle output caching. Health is at `/health`; metrics are at `/metrics`. Use
Compose `stop` to pause the pilot while retaining its named volume. Do not use
`down -v` unless intentionally discarding it.

Protocol verification is recorded in
`~/dev/.remote-dev/build-cache/protocol.json`; build relocation results belong in
the optimisation implementation report. [Gradle's cache documentation](https://docs.gradle.org/9.7.0/userguide/build_cache.html)
defines the HTTP protocol and local/remote lookup behavior.
