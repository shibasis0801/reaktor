# Graph-first service contracts: design and status

Written 23 October 2026, updated 24 October 2026. Owner: Shibasis.

This is the only service layer now. The legacy `Service` / `RequestHandler` API is removed, and every service in reaktor and BestBuds is a contract. Nothing is committed: the work sits in the reaktor and BestBuds working trees. reaktorServer runs the current version in production since 24 October 15:16. The BestBuds workers run the first version (before the root classes in §1) since 23 October; the current worker code waits on a lockfile another session changed (§6).

The layer lives in three modules:
- `reaktor-service`, package `dev.shibasis.reaktor.service.contract`. It holds contracts, links, interceptors, outcomes, channels, joins and sockets.
- `reaktor-graph-runtime`, package `dev.shibasis.reaktor.graph`. It holds the graph nodes and the port delegates.
- `reaktor-cloudflare`. It holds the Hono mount, the binding links, the Cloudflare Queues adapter and the PartyServer socket adapter.

It borrows a few concepts from the v2 drafts, though full v2 is not final:
- port types carry family and role;
- interceptor incidences are ordered by position;
- channels declare distribution, delivery, ordering and dead-letter;
- the keyed temporal join (H11);
- compatibility is computed from schemas;
- outcomes separate a rejection, an unavailable server and an unknown effect.

## Status at a glance

| Area | Work | State |
|---|---|---|
| Contracts | One self-typed root class per service, which the client and every server inherit; one line per `get`/`post`/`put`/`patch`/`delete`; servers `handle` what they host and the rest answers 501 `Unavailable`; routes, effects, idempotency, deadlines; fingerprint from schemas | Done; every service uses it |
| Links | `LocalLink`, `HttpLink`, `SocketLink`, `QueueLink`; deadline propagation; retry with budget | Done; `HttpLink` carries every BestBuds app call to the workers |
| Interceptors | Position, side, family; process, graph, node and endpoint scopes; visible in the graph shape | Done |
| Telemetry | `Spans` writes `ServiceSpan`s with graph definition ids. The BestBuds worker installs it with the build revision and takes the environment from each request | Done; a tier bug the JS tests caught is fixed (§5) |
| Graph | `serve` / `remote` / `relay` / `uses` / `api`; `channel` / `publishes` / `subscribes`; `join`; shape carries contract, channel and interceptors | Done |
| Channels | `Broker`: topics and queues, ordering, overflow, retries, dead letters, settlement for platform queues | Done; no production user yet |
| Joins | `TemporalJoin`, `Flow.joinWithin`, `JoinNode`, `firstSuccess`, `quorum` | Done; no production user yet |
| Sockets | One frame protocol for calls, streams, publishes and subscriptions; reconnect handling | Done; no production user yet |
| Cloudflare | Each BestBuds worker passes its `Served(XServer())` list to `bestBudsWorker`, which serves them in a graph behind an access guard and mounts it with `Hono.mount`; `bindingLink`, `durableObjectLink`, `QueueLink`, `Graph.queue(batch, env, ctx)`, `PartySockets` | Done; 16 worker files serve contracts |
| Spring | `Served.toRouter()`, `Iterable<Served>.toRouter()` | reaktorServer mounts the auth contract with it |
| Legacy | `Service`, `RequestHandler`, the seven `*Handler` classes, `ServiceNode`, `ServiceTransport`, `ServiceInterceptor`, `SpringRouter`, `JsRequestHandler` | Removed (15 files) |
| Migration | reaktor, BestBuds app and workers, engine and kernel, reaktorServer | Done in the working trees (§5) |
| Device runs | Samsung: 31 of 65 flows pass, no failure from the migration. iPhone: not run, because the device plan needs review in Hangar | Android done; iOS waiting on the owner (§5) |
| Hangar | BestBuds DevTools streams `rootGraph.shapes()`, so contract, channel and interceptor shapes reach Hangar; `Compatibility` is ready for drift checks | Data flows; no UI yet |
| Deploy | BestBuds workers and reaktorServer | reaktorServer: current version live since 24 Oct. Workers: first version live since 23 Oct; the current version waits on a lockfile another session changed (§6) |

## 1. The model

A service is one abstract root class. It names every operation once, with its route, types, effect and idempotency. Its client and its servers both inherit from it:

```kotlin
abstract class Guestbook(link: Link = Unlinked) : Service<Guestbook>("guestbook", link) {
    open val sign by post<Sign, Signed>("/guests/{guest}", idempotency = Idempotency.Keyed, status = 201)
    open val read by get<Page, Entries>("/entries", effect = Effect.ReadOnly)
    open val live by stream<Since, Entry>()
    val signed = topic<Entry>("signed")

    override fun client(link: Link): Guestbook = Client(link)

    class Client(link: Link) : Guestbook(link)

    companion object : Guestbook()
}

class GuestbookServer(private val book: Book) : Guestbook() {
    override val sign = super.sign.handle { request -> book.sign(request) }
    override val read = super.read.handle { request -> book.page(request) }
}
```

Reading a root is reading the API:
- `get`, `post`, `put`, `patch` and `delete` are one line each. `call` binds `POST /<id>/<name>` when no route is given, and `stream` declares a live read.
- The client and every server are subclasses, so the IDE jumps from an operation to each class that answers it.
- A server answers what it overrides with `handle`. An operation it does not override goes over the service's link. On the default `Unlinked` link that is a 501 `Unavailable`, which `Retry` never retries. So a mock can answer two operations and a partial server can forward the rest to a real link.
- `super.sign(request)` inside a handler decorates the parent's answer.
- The companion object is the prototype: `Guestbook.sign` is the operation, `uses(Guestbook)` and `api(Guestbook.sign)` are typed ports, and `Guestbook.client(link)` builds a client.
- `Service<Self>` is self-typed, so `uses(Guestbook)` and `serve(GuestbookServer(book))` both infer `Guestbook`, never the companion's type or the server's type.
- `inProcess<S>()` reaches a member that cannot cross a wire, such as a picked media file. It works only over a `LocalLink`.

Everything runs through one exchange path. A call, stream, publish or delivery becomes an `Exchange`. The exchange runs through an ordered interceptor chain and then a transport.

Interceptors are sorted by `position`: Telemetry 100, Deadline 200, Retry 300, Security 400, Application 500. Ties are broken by scope (process, graph, node, endpoint), then by graph depth, then by installation order. Each interceptor declares the sides and families it applies to.

The graph is where services are wired. Port types encode the family and the role:

| Node | Provides | Consumers declare |
|---|---|---|
| `graph.serve(GuestbookServer(book))` / `graph.remote(Guestbook.client(link))` | `contract:<id>` and one `contract:<id>/<operation>` per call | `val api by uses(Guestbook)`, `val sign by api(Guestbook.sign)` |
| `graph.relay(node)` | every port of `node`, wired back to it | (the same consumers) |
| `graph.channel(channel, outlet?)` | `publish:<id>`, `subscribe:<id>` | `val out by publishes(channel)`, `val inbox by subscribes(channel) { … }` |
| `graph.join(left, right, result, within, …)` | (consumes both inputs, publishes the result) | — |

`autoWire` connects these ports like any other port. A `Relay` re-provides another node's ports in its own graph, with a real edge back to the source, so one graph can stand in for another and the shape still shows where each call goes. A relay takes the relayed node's name and kind, so a relayed service reads as a service in the shape.

Interceptors installed with `graph.intercept(...)` reach every service and channel in that graph and in its child graphs.

`graph.shape()` carries three things for each node:
- `contract` (a `ContractShape`);
- `channel` (a `ChannelShape`);
- `interceptors` (the ordered chain).

The definition digest includes the contract shape and the interceptor chain, so changing either is a change of definition.

## 2. Semantics

**Outcomes.**

| Failure | Meaning | Retried by `Retry` |
|---|---|---|
| `Rejected` (4xx, optional typed `problem`) | The domain said no. | Never |
| `Unavailable` (408 / 429 / 503, optional `retryAfter`) | Not attempted. | Always |
| `OutcomeUnknown` (504 or a broken connection) | The effect may have happened. | Only when the operation is retriable |
| `ContractDrift` | The answer did not parse. | Never |

`reject(problem)` throws a typed problem; `problemAs<P>()` reads it on the client. `Throwable.reply(subject)` and `failureOf(status, body, subject)` hold the one mapping in each direction. HTTP and sockets both use them.

**Retriable operations.** An operation is retriable when its effect is `ReadOnly` or `LiveRead`, or its idempotency is `Natural` or `Keyed`. A `Keyed` call carries a fresh `idempotency-key` per logical call, and every retry reuses it. `RetryBudget` bounds retry amplification: 10 tokens, and a success refills 0.1.

**Deadlines.**
- A call's deadline is the earlier of the caller's remaining deadline and the call's own deadline. The default is 25 s.
- The wire carries the remaining milliseconds (`reaktor-deadline`) rather than an absolute time, so clock skew cannot expire calls.
- A server answers 504 `Unavailable` for an exchange that arrives after its deadline.
- A client that gets no answer in time gets `OutcomeUnknown`.

**Traces.**
- Every exchange is a span. `traceparent` and `baggage` (`reaktor-session`) travel in the metadata over HTTP, sockets and queue envelopes, so publish and delivery spans share the sender's trace.
- `Spans` records the definition ids of the node, port, wire and graph, and the activation (the root graph id).

**Channels** (`Topic` = fan-out, `Queue` = competing consumers).
- Ordering is `None`, `Total` (one worker), or `PerKey { key }`, which keeps per-key order across workers by partitioning.
- Overflow is `Suspend`, `DropOldest`, `DropNewest` or `Reject`; `Reject` answers 503.
- Each failed delivery is retried with backoff up to `maxAttempts`, then goes to the dead letter. A `Rejected` handler failure goes to the dead letter at once.
- A channel's `deadLetter` is another channel. In the graph it is a real wire: `publish:<dead-letter id>`.
- `Broker.receive(envelope)` settles one platform-delivered message to `Ack` or `Retry(after)` using the same policy. The Cloudflare Queues consumer uses it.

**Queue envelope** (what `QueueLink` sends):

```json
{"envelope": "reaktor/1", "metadata": {"traceparent": "…", "baggage": "…"}, "message": {…}}
```

`unseal` also accepts a bare message, so producers that predate the envelope keep working.

**Socket frames** (JSON text, `type` discriminator, one socket for everything):

| Frame | Direction | Purpose |
|---|---|---|
| `ask` / `answer` | client → server / back | Unary call; the answer reuses the HTTP status and problem body |
| `open` / `item` / `end` | client → server / back | Stream; `end` carries a failure status when the stream fails |
| `publish` / `answer` 202 | client → server / back | Publish into a server channel |
| `subscribe` / `deliver` | client → server / back | Subscribe to a server channel |
| `cancel` | client → server | Cancels a call, stream or subscription |

The client recognises a reconnect through `SocketTransport.connection`. When it sees one, it does two things:
- calls and streams sent on the old connection end with `OutcomeUnknown`;
- subscriptions are sent again on the new connection.

When a client graph declares `graph.channel(news, socketLink)`, the channel subscribes on the server only while it has local subscribers.

**Temporal join** (`TemporalJoin(within, lateness)`).
- An event is late when its time is before the watermark, which is the latest event time minus `lateness`. Late events are counted and dropped.
- Events of the same key whose times differ by at most `within` are paired.
- Buffered events older than the watermark minus `within` are evicted.
- `Flow.joinWithin` and `JoinNode` drive the same state machine.
- `firstSuccess(…)` and `quorum(n, …)` cover the call-side joins.

**Compatibility.** `Compatibility.between(client, server)` compares the two contract shapes. The request must be readable by the server and the response by the client:
- a field the reader requires must be written;
- enum values and sealed variants must be known to the reader;
- an integer may be read as a number, and an enum as a string.

The `version` field is only a label. The fingerprint (FNV-64 over the canonical shape) changes only when routes or schemas change.

## 3. Platform adapters

**Cloudflare Workers.**
- `Hono.mount(graph)` serves every server `ContractNode`.
- `bindingLink("SERVICE")` and `durableObjectLink(namespace, name)` reach other Workers and Durable Objects.
- Inside a handler, `exchange().cloudflare[binding]` resolves bindings.

**Cloudflare Queues.**
- Producer: `graph.channel(Persistence.finish, queueLink("MESSAGE_PERSISTENCE"))`.
- Consumer: `fun queue(batch, env, ctx) = graph.queue(batch, env, ctx)`.
- Each message is settled through the channel's broker. A failure becomes `message.retry({ delaySeconds })`. The final attempt goes to the dead letter and is acked.

**PartyServer.**
- `PartySockets(graph, exposed = listOf(…))` keeps one `SocketSession` per connection. It answers heartbeat pings and closes the session with the connection.
- Contracts are exposed through their own interceptors. Channels are exposed only when listed.

**Spring WebFlux.** `served.toRouter()` and `graph.served().toRouter()` build the router.

## 4. Files

- `reaktor-service/src/commonMain/.../contract/`:
  - `Service.kt` (the root class, `Declares`, `Unlinked`), `Operation.kt` (`Call`, `Stream`, `Route`, `Effect`, `Idempotency`);
  - `Channels.kt`, `Exchange.kt`, `Interceptor.kt`, `Outcome.kt`, `RouteBinding.kt`;
  - `Link.kt`, `Served.kt`, `HttpLink.kt`, `Resilience.kt`, `Spans.kt`, `Shape.kt`, `Compatibility.kt`;
  - `Broker.kt`, `Join.kt`, `Socket.kt`.
- `reaktor-service/src/jvmMain/.../contract/SpringContracts.kt`.
- `reaktor-service/src/commonTest/.../contract/`: `ContractTest.kt`, `WireTest.kt`, `BrokerTest.kt`, `JoinTest.kt`, `SocketTest.kt`.
- `reaktor-graph-runtime/src/commonMain/.../graph/`: `ContractNode.kt`, `Relay.kt`, `ChannelNode.kt`, `JoinNode.kt`; `core/GraphShape.kt` (shape fields).
- `reaktor-graph-runtime/src/commonTest/.../graph/`: `ContractNodeTest.kt`, `ChannelNodeTest.kt`.
- `reaktor-cloudflare/src/jsMain/.../cloudflare/`: `Contracts.kt`, `Queues.kt`, `Sockets.kt`. `Router.kt` and `Bindings.kt` gained small helpers.
- BestBuds `modules/app/src/commonMain/.../app/`: `Backend.kt` (`Backend`, `Variant`, `Remote`, `Mocked`, `Build`) and `shared/runtime/Wiring.kt` (`Wiring`, `Wires`).

## 5. The migration

**Scope.**
- reaktor: 8 contracts, about 28 operations (`reaktor.auth`, `reaktor.media`, `reaktor.notification-dispatcher`, `reaktor.realtime-token`, `reaktor.apps`, `reaktor.board`, `reaktor.devtools`, `device.probe`).
- BestBuds: 33 contracts, about 168 operations. They cover the app's client side and its mocks, the Cloudflare workers that serve them, the engine and kernel, and reaktorServer.

**How the app is wired now: four backend graphs.** BestBuds has one graph type for its backends, `Backend`, with one member per service (`messaging`, `circles`, `plans` and fifteen more), each a `Provides<Api>`. Four graphs implement it:
- `Remote(user, deployment)` is the dev or the prod backend: HTTP clients with the user's auth, plus the in-process services built on them (`DevProfile`, `DevGroups`, …).
- `Mocked(user, world)` serves every mock against the `MockWorld`.
- `Build(user, wiring, remote, mocked)` is the echo graph. Each member is a `Relay` of the same member in the variant the wiring picks, so a session can run on dev with only `plans` mocked, or the other way round.
- A session has one of each. Only the session's own deployment can be its `Remote`, because a dev token is not a prod token.

Variant members are lazy, so a service nobody relays is never built: an all-mock session opens no realtime socket. Members that belong together follow their parent: `chatStream` and `userStream` follow `messaging`, `rounds` follows `circles`, and `compass` follows `trials`.

Feature graphs take the `Backend` and relay what they use: `val service = relay(backend.circles)`. Per-service facts come from the variant that serves it: `backend.origin(Backend::plans).clock`, `.live`, and `.label`, which keys the offline caches. No code names a service or a variant with a string. Names exist only as data: the launch argument `backends: "mock,plans:dev"` and the developer screen's choices become a `Wiring`, and `Build` resolves them against its own members' names and its variants' labels. An unknown service or variant fails the session at start.

**Wire compatibility with installed apps.**
- Routes are unchanged.
- Request and response classes keep their `@SerialName`s.
- Compared with HEAD, three serial names are gone from `reaktor-service`: two test types and the legacy `ServiceContract` descriptor. Requests and responses never carried any of them.
- The new `HttpLink` decodes with the same framework `json` the legacy client used.
- A non-2xx answer passes through the interceptor chain as a typed failure (`Rejected`, `Unavailable`, `OutcomeUnknown`), so `Retry` and `Spans` see what it was. `Link.call` then hands the caller the decoded body, as the legacy client did. Only an answer whose body does not parse reaches the caller as an exception.

**A tracing bug the JS tests caught.** The worker used to stamp every span with the global `BESTBUDS_TIER`. `validatedSpanRows` rejects a whole batch when a span's environment differs from the intake's tier, and a rejected batch is never acknowledged, so it blocks every batch after it. `BestBudsTracing` now reads the tier from each request's `APP_ENV` and passes it through `ServiceCall` attributes. `WorkerIngressTelemetryTest` went from 8 failures to 9/9.

**Verified, 24 October 2026, after the root classes and the four backend graphs.**
- Compiles: the BestBuds app for JVM, JS, Android and iOS (arm64), with its tests, plus engine, kernel, reaktorServer, reaktorDesktop and reaktorCli.
- reaktor JVM: 398 tests across 10 modules pass, including the relay test.
- BestBuds app JVM: 262/262. BestBuds app JS, which includes the worker code: 308/308 (`./gradlew :app:jsNodeTest -x :pnpmInstall` runs them without the kernel).
- kernel: 452 pass, 11 skipped. reaktorServer: 22 pass, 1 skipped.
- engine: 597 tests, 17 fail. 16 failed the same way before this work (other sessions' Surface work). The 17th expects trace text that another session changed in `AppGraphTrace.kt` at 13:01.
- Production: the Karate auth suite (13 workers) passes 42/42 before the deploys, after the workers' deploy and after the server deploy.
- Real dev data loads in the new client (friends, chats, messages, profile), and a real message went from Manab to Shibasis on dev (`telemetry-send`).

**Samsung (Android), dev tier, new build (versionCode 27).** 31 of 65 flows pass. None of the 34 failures comes from the migration:

| Cause | Flows |
|---|---|
| Dev accounts `shibasis` and `shibasis1` are no longer buds, so there is no chat or bud row. The old build fails the same way | chat-actions, chat-draft-restore, chat-media, chat-offline-send, chat-photo-upload, chat-voice-real, chats, group-edit, group-info, people, report-person, session, user-tour, notifications |
| The dev server answers "Can't reach…" for `/plans` and `/invites`. Routes and server are unchanged, and the old build shows the same error for plans (invite-real was not run on the old build) | plans, invite-real |
| Flows still look for the "Real server" label, renamed "Dev server" on 16 October | circle-dev, circle-week-dev, developer, discover-dev, plans-dev, real-app |
| Flows that no longer match committed product code | discover (the tab shows Circle discovery), chat-mentions (Circle-only people show an age range: "Leo, late 20s"), offline-reopen and relaunch (relaunching without `language` gives the default Handwritten Dark look), circles-next ("YOU.RE IN LINE": maestro-runner 1.1.27 reads it literally; lobby and new-user match the literal label and pass) |
| Leftover dev data from earlier runs | compass (the screen showed three "Goa in December" pins, not the two pins the harness seeds; not investigated further), edit-profile (an interest called "maestro") |
| Fail the same way on the old build (versionCode 26, from `46ae8013`) | feature-graphs, invite-signed-out, notifications-inbox |

telemetry-receive was not run; it needs a second phone sending at the same time.

**iPhone (iOS 27.0.1), dev tier.** Not run. The phone still has an old build (1.0, build 1) without this work, and a new build could not be made in this session:
- The kernel's `mobileDeviceFlows` node answers "needs a reviewed plan; run it from Hangar until deploy.run lands".
- Plain Gradle stops before the kernel-owned `:appDarwin:generateReleasePlan`, and the suite then fails when it reads the plan.
- An iOS compile (`:app:compileKotlinIosArm64`) was not run either.

What is known: only one iOS-only file changed (`DarwinAuthAdapter`), and it makes the same `AuthService.client(HttpLink(...))` call that compiles in the Android and web adapters. No iOS source set uses a removed type, and the shared code compiles for JVM, Android and JS.

**Test infrastructure found along the way.**
- offline-reopen turns airplane mode on and, when it fails, never turns it off. In a run that continues past failures, every later flow then has no network.
- Kernel runs fail while other sessions change build declarations ("Build model changed", "Gradle execution host or configuration changed", and a 10-second `git ls-files` timeout). Re-exporting the build model and retrying gets through.

## 6. Deploy status

**reaktorServer: the current version is live.**
- The first rollout (24 October, late morning) crashed at start, because two beans were named `serverAccess`. It was rolled back to the previous image (`…@sha256:de84f0ac…`) after about 6 minutes of API downtime, and one bean was renamed `authAccess`.
- Redeployed 24 October 15:16 as `bestbuds-app-server@sha256:7c23bb29…` (tag `service-contracts-20261024-2`), after the image booted against a throwaway Postgres. The rollout took 32 s. The deployment uses `maxSurge: 0`, so the API answers 502 for a few seconds while the pod is replaced.
- After the rollout the endpoint probe matches the baseline with one change: `POST /auth/pat/verify` with a body that lacks `rawToken` used to answer 500 and now answers 400 with the parse error. The Karate auth suite passes 42/42.
- To roll back, patch `app-server` to `…@sha256:de84f0ac…`.

**BestBuds workers: the first version is live, the current version is blocked.**
- 16 workers were deployed to production on 23 October at about 23:05 with the first version of this layer. Routes and serial names have not changed since, so they serve the current apps. Their previous versions are saved for rollback.
- The current worker code (root classes and `handle`) is not deployed. Every worker deploy runs pnpm, and dependeasy makes pnpm check the install against `pnpm-lock.yaml` first. That check fails: another session's uncommitted lockfile edit (24 October 14:05, which adds `postgres` and the board package) dropped `react` and `react-dom` from the `reaktor-devtools` importer, which still declares them. The committed lockfile has them. The fix belongs to that session: restore the two entries, or regenerate the lockfile with the generated packages in place.
- `analytics` stays out until the owner applies the pending ClickHouse migration. This session's request to apply it was refused.
- The D1 migrations (`socialMigrations`, `notificationMigrations`) are left out, because their plans are withheld outside the kernel.

Once the lockfile is consistent, plan first (`-Pdependeasy.deployAction=plan`), then deploy:

```bash
./gradlew --no-daemon -x :generatePackageScripts -x :pr -x :clickhouse -x :workers:socialMigrations -x :workers:notificationMigrations :workers:deployAccountPurge :workers:deployArchiver :workers:deployBot :workers:deployCampaignEngagement :workers:deployChat :workers:deployCircleFormation :workers:deployConfig :workers:deployDataProbe :workers:deployImageModerator :workers:deployMedia :workers:deployMessaging :workers:deployNotification :workers:deploySocial :workers:deploySticker :workers:deployTextModerator :workers:deployVerification -Pdependeasy.environment=prod --max-workers=1
```

Then re-run the Karate auth suite (`:workers:workerKarateAuth -Pdependeasy.environment=prod`) and the endpoint probe.

The web app (`appWeb`) was not deployed.

## 7. Next steps, highest leverage first

1. **Deploy the current worker code** once the lockfile is fixed (§6), then re-run Karate and the probe.
2. **Apply the ClickHouse migration** and deploy `analytics`.
3. **Repair the dev tier and the stale flows** so the device suites can catch regressions again:
   - make `shibasis` and `shibasis1` buds again with a direct chat;
   - reset the compass and profile leftovers, and find out why dev `/plans` and `/invites` fail;
   - pass `language` on relaunches, and fix discover, chat-mentions and circles-next;
   - turn airplane mode off in an `onFlowComplete` hook in the offline flows.
4. **Migrate BestBuds message persistence.** Today `MessageFinish.kt` hand-writes the queue producer and the batch consumer. Replace them with a typed `queue<AcceptedChatMessage>`, `queueLink("MESSAGE_PERSISTENCE")` and `graph.queue(...)`. This needs a deploy of the messaging worker.
5. **Hangar.**
   - Draw `publish:` / `subscribe:` wires as channel edges, and show the interceptor chain on contract nodes.
   - Run `Compatibility.between` against the deployed shape to flag drift.
   - The Hangar UI is in BestBuds `engine`, where other sessions are active.
6. **Durable joins and queues on Durable Objects.** `TemporalJoin` is a pure state machine. A Durable Object can snapshot it, so joins survive Worker isolate restarts.
7. **A Miniflare test** of `Graph.queue` and `PartySockets` in `reaktor-cloudflare`.
