# Expressive-code scan: reaktor and BestBuds

Written 24 October 2026, redone across both repositories on 25 October 2026, updated 27 October 2026. Owner: Shibasis.

The service and navigation work removed three habits:
- one idea declared several ways;
- one fact written twice, for example in a schema and again in its parser;
- wiring done with statements where a declaration would do.

All three are still common outside the code that work touched.

The biggest remaining case is the tooling half: the kernel, the conductor, DevTools and Hangar's agent reads.
- It has its own contract model beside reaktor-service's.
- It declares 88 MCP tools with hand-written schemas.
- It builds or reads JSON by string key in about 4,400 places.

Between 25 and 27 October the app's service findings were fixed and deployed (§0). What is left in the app is the message-finish queue payload, two empty acknowledgements and the legacy `SocialApi` that installed apps still call (§3). Navigation is done apart from its payload type parameter (§4).

Counts cover main (non-test) Kotlin in `reaktor` and `bestbuds`, about 342,000 lines. They leave out build output, vendored flatbuffers, benchmarks and `bestbuds/agent` unless a finding says otherwise.

| | Change | What it removes |
|---|---|---|
| §1 | One contract model for everything callable | tooling's `Operation`/`SafetyClass`/`Outcome`; 88 hand-written MCP schemas; DevTools string commands; a third MCP layer |
| §2 | Typed results in the tooling | most of the ~4,400 JSON reads and writes by key |
| §3 | Typed service outputs in the app | 1 legacy combined response (23), 1 hand-written sign-in check (49), 1 `Request` input (402 transport parameters); `pathParams` reads and hand-written worker fetches are gone |
| §4 | Finish declarative navigation | the payload type parameter, written at 43 sites; map payloads, pane graph classes and hand-listed wiring are gone |
| §6 | Merge parallel implementations | ~2,200 unused editor lines, 39 JSON configs (77); backoff loops and base64 decoders are gone |

## 0. Done

**24 October**

- **Exits are declared, not assigned.** A binding says where its screen can go (`val chat by exit<ChatBinding>()`), and `autoWire` connects each exit to the one route bound by that type.
- **A route and its screen are one line**, for example `Route("/circles", ::CirclesHomeBinding, ::CirclesHomeScreen)`.
- **Service roots are open classes used as values**: `MessagingApi() over link`, `by uses(MessagingApi())`.
- **The agent guides teach these idioms**: `bestbuds/AGENTS.md` and the `reaktor-app` skill.

**25 October** (all 16 workers deployed to PROD twice; Karate 106/106 after each)

- **Eleven app services answer with typed outputs.**
  - The eleven: Circles, Rounds, Plans, Trials, Compass, Invites, Onboarding, Verification, Me (the profile), Stickers and Notifications.
  - Each operation declares its own output and fails with `reject(status, message)`.
  - The client reads them through `Outcome`.
- **Installed apps keep decoding.**
  - A service's `wire` (`Refusals`, `LowercaseRefusals` or `Failures`) writes each reply in the shape store builds already parse: the old serial names and, for the `Failures` family, the `hack` field their failure classes require.
  - `Wire.Tagged` gained `fields` for that field.
  - Tests decode the new replies with copies of the old classes.
- **Workers resolve the caller once.** `signedIn { }` hands the handler `me` and the database. `answering { }` turns an unknown error into one 500.
- **The legacy `SocialApi` lives in the worker** (`jsMain/.../data/social/SocialApi.kt`) and only serves installed apps. The app no longer has `SocialService`, `SocialLink`, `fetchMyProfile` or `updateMyProfile`.
- **The client adapters are gone.** `DevProfile`, `DevPeople`, `DevNotifications` and `DevGroups` were deleted once the workers served the new APIs.
- **Stickers.**
  - Their reply names changed on 11 October, so store builds could not read sticker replies. The typed service restores the old names.
  - `StickerRepository` and `StickerCatalog`, which nothing read, are deleted.
- **Start-up.** `hydrateAnalyticsSession` moved into `SessionHydrationInteractor`, which now also refreshes the geo cell when the app resumes.
- **Navigation wiring (§4).**
  - `Graph.wire()` connects exits across the whole application tree, so the `autoWire` lists in `Session.init` are gone.
  - Child graphs inherit their parent's dispatcher.
  - `HomeTab` is merged into `SignalTab`.
- **Quick merges (§6).**
  - One `Backoff` value replaces the hand-written backoff loops.
  - `rememberNow(every)` replaces the copied clock ticks.
  - Shared JSON configs cut `Json { }` instances from 77 to 42.
  - No hand-written `decodeBase64` is left.

**25 October, later** (all 16 workers deployed to PROD a third time)

- **Messaging is typed.**
  - `MessagingApi` has nine operations, each with its own input (`ChatPage`, `NewMessage`, `MessageRef`, `MessageEdit`, `Reaction`, `ChatFlagsChange`, `BotMessage`) and its own output.
  - Deleted: the sealed `MessagingRequest` and `MessagingResponse`, `MessageInput`, `ChatSocketSend`, `MessagingLink`, `MessagingService` and eight free helpers such as `fetchMessages` and `toggleMessageReaction`.
  - Someone outside a chat now gets 403 "You're not in this chat." Installed apps still read it as `Unauthorized`.
- **The chat socket answers with the message.** `ChatStream.publish` returns the saved `ChatMessage` or throws `Rejected(400)` with the server's words, and the outbox reads it as an `Outcome`.
- **Workers sign their own calls.** One `ServiceAccount` interceptor on the internal links replaces the service headers that were added to each request by hand. `serviceUserHeaders` is gone.
- **Bot and Moderation are typed.**
  - `BotService` has one operation, `chatRespond` (`BotTurn` to `BotReply`). The unused `generate` is deleted, and the refusal text moved into the bot.
  - Text and image moderation take `TextCheck` and `ImageCheck` and answer a `Verdict`.
- **The chat room answers @BudBot itself.** It reads the history and saves the reply in Postgres, then delivers it like any other message. It no longer borrows the user's identity to call messaging.
- **Typed handlers keep the caller's trace** in their background work.

**25 October, evening**

- **The chat and user rooms serve typed operations.**
  - `ChatDelivery` holds the room's seven internal routes (message, moderation, read receipt, @BudBot, round, authority, publish) and `UserRoom.tell` the user room's one, at their old paths.
  - A room answers them with `Served.answers(request)` and `Served.respond(request)` (reaktor-cloudflare) in place of six hand-matched paths and `js("new Response(…)")`.
  - Workers call them through `BestBudsClients`, so `serviceHeaders`, `BestBudsServiceAuth.kt` and the six hand-written fetches are gone.
- **One helper runs work after the response.** `CloudflareContext.waitUntil("what") { … }` keeps the caller's trace, logs a failure under its name, and replaces 13 `GlobalScope.promise` plus `waitUntil` pairs.
- **Config, Circle formation and Campaign engagement answer typed outputs.**
  - Config answers the app's own `Config`, under the name installed apps decode.
  - Each of Circle formation's seven operations has its own input and output. The desktop's Circles pane reads a result by the operation it ran, and the operations bridge calls the served service with the Cloudflare context instead of a `LocalLink`.
  - Campaign engagement answers `Engagement` and `Engagements`.
- **Web and Analytics are typed in code, not yet deployed.** The website worker waits on the web deploy decision, and the Analytics worker waits on the ClickHouse migration.
- **Found: legacy `Request` inputs put their transport headers in the JSON body.** A subclass that overrides `headers` serializes it, so calls made with `headers = userHeaders(user)` also carry the bearer token in the body. Converting the remaining `Request` inputs removes this.
- **reaktor:** a graph call port with no input is called as `port()`.

**25 October, night** (all 16 workers deployed to PROD a fourth time; Karate 53/53; PROD phone flows 3/3)

- **Notification dispatch is typed.**
  - `NotificationDispatchApi` has four operations with data-class inputs (`Notice`, `CategoryPreference`, `ForgottenUsers`, `StaleNotices`) and one output, `Fanout`.
  - They replace `Request` subclasses, so these bodies no longer carry transport headers. The outbox's stored JSON keeps its field names.
- **One operations endpoint.**
  - `Hono.operations(worker) { read(…); write(…) }` serves the catalogue and every operation for Circle formation and the data probe, in place of two hand-written routers.
  - An unknown operation or a bad request id answers 400. A failed operation answers 200 with `ok = false` and its error.
- **Found: dependeasy's deploy check can fail a live deploy.** It reported "Active version does not carry this run's message" for messaging while Cloudflare already served that run's version.

**25 October, afternoon** (app, Hangar and reaktor code; workers deployed to PROD a fifth time; Karate 53/53)

- **Navigation (§4) is done** apart from the payload type parameter: typed route classes, `Root { … }`, graph-level `replaceWith`, `popToRoot` and `currentRoute`, and one verb, `consumes`, for a port, a service client or one operation.
- **One set of byte conversions.**
  - `ByteArray.toUint8Array()`, `ArrayBuffer.toByteArray()` and `ArrayBufferView.toByteArray()` live in reaktor-core.
  - They replace nine private copies in reaktor-cloudflare and reaktor-io.
  - The R2 bindings use the browser's `ArrayBuffer` instead of a placeholder class.
- **reaktor-db's typed `SqlBuilder` is deleted**, with `SqlAdapter.execute(Statement)`, its only caller.
- **Three more `Json { }` instances are gone.** OTLP span export uses the default `Json`; the MCP inspector and the database query view use `prettyJson`.
- **`!!` removed: 12 in the app and 43 in reaktor.** The app and reaktor's runtime modules (db, io, graph, cloudflare, auth, ui) have none left.
  - A row's required columns are read with `cursor.string(i)`, `long(i)` and `bytes(i)`, which say which column was null.
  - Cached values are written as `driver ?: createDriver().also { driver = it }` or `by lazy`.
- **Dead code deleted:** reaktor-io's server health probe, which returned before doing anything, and an unused Android content-URI copier.
- **`RepositoryNode.cached(key) { fetch }`** replaces `writeThrough`, `write`, `writeAndGet` and `CacheResult`.
- **Durable Objects and PartyServer share one `blockConcurrencyWhile`**, with no nullable result.
- **Found: the dev sign-in seeds run out after about three phone suites.**
  - Each dev sign-in spends one of 96 seeds per account. Once most are spent, a sign-in tries up to 24 spent seeds, takes 8 to 30 seconds, and the flows time out.
  - Reseed with `scripts/seed-dev-auth-fixtures.mjs`, then rebuild.
- **Found: a stale test.** Since 24 October every CORS policy also allows the headers reaktor sends (`Metadata.Sent`); `CorsTracePropagationTest` now expects them.

**25 to 27 October** (app, workers and reaktor; all 16 workers deployed to PROD a sixth, seventh and eighth time; Karate 53/53 after each; PROD phone flows passed after the sixth and seventh, and the eighth's wait on the Samsung test phone being unlocked)

- **Instrumented wrappers go by their own names (§8).**
  - No file imports `userPathLaunch as launch`, `signalBackground as background` or the other aliases. Files call `userPathLaunch { }`, `UserPathEffect(…)`, `signalBackground(…)` and the rest by name, and lambda labels follow (`return@userPathLaunch`).
  - Sign-in, age-gate, verification and session start-up (19 calls) used the node's plain `launch` and recorded no user path. They now run through `userPathLaunch`.
- **Store reads say when they fail (§9).** `ObjectState.getOrNull()` logs a failed read, returns null and rethrows cancellation, which `runCatching` swallowed. It replaces `runCatching { state.get() }.getOrNull()` at 8 app sites.
- **Boundaries use `outcome {}` (§9).** 19 more `runCatching { … }.getOrNull()` calls at service and device boundaries, in 12 files, became `outcome { … }.valueOrNull` or the app's `attempt`. Both rethrow cancellation and classify the failure, and `attempt` also records it.
  - In the app: notification permissions and device tokens, session renewal, the chat roster, sticker packs and the chat page cache.
  - In the workers: realtime-token requests, the verification webhook, campaign engagement, message finishing and onboarding.
- **Matching takes plain inputs (§3).** `enqueue` and `status` take nothing and `pause` takes `MatchingPause(paused)`. The lobby calls `enqueueOperation.attempt()`. Bodies no longer carry header maps, and installed apps' bodies still decode.
- **The legacy `SocialApi` takes plain inputs (§3).**
  - `SocialRequest` is now a namespace of data classes such as `BlockUser`, `AddChatMembers` and `UpdatePrivacy`. An operation that only needs its path value takes a `String`, and one that needs nothing takes `Unit`.
  - The worker answers through `legacy("…") { }`, which signs the caller in and maps failures to the old `SocialResponse.Failure` classes.
  - Installed apps' bodies, with their `type`, `headers`, `queryParams` and `pathParams` keys, decode into the data classes (`LegacySocialWireTest`).
- **Every app service has typed outputs (§3).** The Archiver and AccountPurge return `BackupStatus`, `BackupResult`, `Restored`, `PurgeResult` and `AccountStatusChanged`, and refuse with `reject`. Only the legacy `SocialResponse` still sets `statusCode`, and `RoomAccess` still refuses realtime tokens through reaktor-auth's `RealtimeTokenResponse(statusCode = …)`.
- **The Archiver and AccountPurge take plain inputs (§3).** `Restore(key)`, `AccountStatusChange`, `PurgeConfirmation(confirm)` and `Unit` replace their `Request` subclasses, and the handlers reach R2, Postgres and D1 through the call (`exchange().cloudflare`). The cron's `?confirm=` query still fills `PurgeConfirmation`.
- **Per-call headers travel as headers (reaktor-service).**
  - `withContext(CallMetadata(headers)) { … }` adds headers to every call made inside it, across graph ports as well.
  - The analytics sink sends the signed-in user's token this way, so `EventBatch` is plain data and the app no longer puts a bearer token in any request body. The Analytics worker reads the caller from the call.
  - That worker still waits on the ClickHouse migration to deploy. The deployed one already reads the header, so the new app works with it.
- **One way to start a cron.** `worker.scheduled("/backup/trigger", env, executionContext)` builds the request that four scheduled workers wrote by hand in JavaScript.
- **Fully qualified names became imports**: 64 BestBuds names in 27 files and 118 library names in 47 files. For example, `ai.bestbuds.app.data.analytics.logEvent(ai.bestbuds.app.data.analytics.BestBudsEvents.SIGNED_OUT)` is now `logEvent(BestBudsEvents.SIGNED_OUT)`, and `kotlin.time.Clock.System.now()` is `Clock.System.now()`.
- **`ChatView` is split (§10).** The 650-line function is now 25 lines that compose five sections: `ChatBar`, `MessageTimeline`, `ChatFooter`, `ChatViewers` and `ChatSheets`.
  - State and actions live in `ChatRoom`, created once per chat, with `ChatSearch` and `VoiceRecording` beside it.
  - Effects say when to act and call a named action, for example `UserPathEffect(room.seeking, rows) { room.seek(rows) }`.
  - Leaving the chat for another screen is one rule: `room.quietly(onPerson)` closes panels and the keyboard first.
- **The Service Layer page describes the code as built** (`bestbuds/targets/reaktorWeb/docusaurus/docs/reaktor-service.md`, 27 October): what the rework changed, declaring, serving and calling a service, how inputs travel, failures, call context, interceptors, links, the graph integration and navigation. It is not yet deployed to reaktor.build.

## 1. One contract model for everything callable (highest leverage)

There are five ways to declare something that an app, agent, CLI or Hangar can call:
- **reaktor-service**: `Service`, `Call<In, Out>`, `Effect` and `Outcome`. Apps and workers use it.
- **reaktor-tooling**: `Operation<I, O>`, `SafetyClass` and its own `Outcome` (`reaktor-tooling/.../operation/Operation.kt:55, 108`). The kernel uses it.
  - `Effect` (`reaktor-service/.../contract/Operation.kt:96`) is `SafetyClass` (`reaktor-tooling/.../ToolingModels.kt:151`) without its four local effects. The two are the same concept twice.
- **Raw `McpTool`s**: 88 of them, 78 in the conductor. Each writes its JSON schema by hand, then parses the same fields again by string.
  - In `agent_tasks_wait` (`AgentWorkspaceMcp.kt:48-58`), `offset` is declared as `buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 200) }` and then read as `it.long("offset", 0)`.
  - The handlers block on `runBlocking`, seven times in that file.
- **DevTools commands**: `AgentCommand(capability, action, arguments: Map<String, String>)` (`DevToolsProtocol.kt:414`).
  - Each handler matches action strings and parses its arguments from the map, for example `"block" -> … arguments["pattern"]` (`HttpTap.kt:91-98`).
  - Device actions in reaktor-tooling are dispatched the same way (`"install"`, `"launch"`, … in `DeviceJvmClient.kt:73-110`).
- **reaktor-cloudflare's own MCP layer**: `McpToolSchema` and `McpToolResult` (`Mcp.kt:19, 78`).

The change is to declare each of these as a service operation:

```kotlin
open class AgentTasks : Service("agent.tasks") {
    open val wait by call<TasksWait, TasksPage>(effect = Effect.ReadOnly)
}

@Serializable
data class TasksWait(val revision: Long, val query: String = "", val offset: Int = 0, val limit: Int = 50, val timeoutMillis: Long = 30_000)
```

- `Effect` absorbs the four local effects from `SafetyClass`.
- The MCP door's approval becomes an interceptor.
- MCP tools, CLI commands and Hangar actions become views of `service.shape`, which already derives schemas from serializers. Bounds such as `maximum = 200` need one annotation next to tooling's `@Describe`.
- This deletes:
  - tooling's `Operation`, `Outcome` and `SafetyClass`;
  - the conductor's hand-written schemas and argument parsing;
  - DevTools' string dispatch;
  - the Workers MCP layer.

## 2. Typed results instead of JSON passed around by key

The kernel, Hangar engine, conductor, tooling and dependeasy main code has about 2,900 JSON writes by key (`buildJsonObject`, `put("…")`) and 1,500 reads by key. Part of that is real external JSON: the Codex and Claude app servers, Cloudflare, Kubernetes, Google and the MCP wire format. Much of it is the code talking to itself:
- **The build model.** dependeasy's `BuildModelReader` returns `JsonObject` pages, and the kernel reads them back by key:
  - `page["truth"]?.jsonPrimitive?.content == "stale"` (`KernelOperations.kt:453`);
  - `inventory.getValue("nodes").jsonArray … getValue("id")` (`:513-516`).
- **The kernel's own snapshot.** The kernel reads its typed snapshot through JSON: `runs()` is `controlPlaneJson().getValue("runs")`, and `run(id)` matches on `it["id"]` (`KernelOperations.kt:361-362`).
- **Kernel operation results.**
  - 12 kernel operations return a hand-built `JsonObject`, and 6 more are `rawOperation`s with JSON in and out.
  - `kernel.watch` assembles its page with about 20 `put` calls (`KernelOperations.kt:116-140`), and names its own continuation by string: `put("operation", "kernel.watch")`.
- **Hangar's agent reads.** Each projection is built by hand: `AppGraphReads.summary` (`AppGraphReads.kt:148`), plus 108 such sites in `DevToolsReads.kt`.
- **Repeated helpers.** The same JSON helpers are rewritten in each module: `JsonObject.string` 5 times, `JsonObject.text` 3, `JsonObject.stringOrNull` 3, `JsonArray?.orEmpty` 3.

The change is a `@Serializable` data class for every page, snapshot and projection, encoded once at the boundary. With §1 these become operation outputs, so agents also get output schemas.

## 3. Service results and failures in the app (in progress)

Counts on 27 October, with 24 October in brackets:
- **Combined responses.** 1 left (23): the legacy `SocialResponse` that installed apps still call. It carries the last `hack` status field (11) and is the only response that sets `statusCode`.
- **Typed failures.** 219 calls to `reject` (3).
- **Workers.**
  - 1 hand-written sign-in check (`currentDataUserOrNull`, in Analytics) (49).
  - No worker fetches another by hand; `serviceHeaders` is gone.
- **Requests.**
  - 1 input still extends `Request` (402 transport parameters): `AcceptedChatMessage`, the message-finish queue payload, which carries the worker context and the trace to the in-process service.
  - No request body carries transport headers; per-call headers go through `CallMetadata`.
  - 6 calls pass `userHeaders(user)` (52), all at the transport edge: the media uploader (2), the two sockets, `userLink` and the analytics sink.
  - No `pathParams["…"]` read is left (55). 3 `queryParams["…"]` reads remain, for pagination and the media path.
- **Empty acknowledgements.** `ChatUpdateAccepted` and `MessageFinished` still extend `Response` with no fields.
- The 5 `else -> error(…)` left map values from the wire (storage bindings, environments, social changes, stores, chat types), not response fallbacks.

Next:
1. **Message finish on reaktor's queue channel.** `QueueLink` and `Graph.queue` carry the trace in the message envelope and hand the consumer the worker context. That retires `AcceptedChatMessage` and `MessageFinished`. It needs a reader for raw bodies still in the queue during the deploy.
2. **`ChatUpdateAccepted` becomes `Unit`** on the chat room's internal routes.
3. **The last boundaries (§9):** login and account deactivation in `UserRepository`, push delivery in `NotificationDispatchService`, and two `runCatching { context }` reads that `contextOrNull` already answers.
4. **Analytics and Web go live** with the next Analytics deploy (after the ClickHouse migration) and the next website deploy.
5. **The legacy `SocialApi`** retires when installed apps no longer call it.

The pattern for each is the one used on 25 October:
- one output per operation;
- `reject` on the server;
- a `wire` that keeps installed apps' names;
- `signedIn { }` in the worker in place of `userHeaders`.

## 4. Navigation and graph construction: finish what exits started

**Done on 25 October, late night** (app, Hangar and Manna code; no worker changes):
- **Typed route payloads.** A route that carries data declares a `@Serializable` route class beside its binding: `ChatRoute(id, title, round)`, `PersonRoute`, `CircleRoute`, `PlanRoute`, `ComposerRoute(circle, edit)`, `GroupRoute`, `TrialRoute`, `InviteRoute(code)`, `OnboardingRoute(step)` and `GalleryRoute(story)`.
  - An exit pushes the route, `chat.push(ChatRoute(id))`, and a screen reads it back, `val (id, title, round) = entry.toRoute<ChatRoute>() ?: return`.
  - reaktor's `payloadOf(route)` and `toRoute<R>()` keep the route's fields in the payload's string map. Deep links, the Inbox's link builder and Studio's page list read that map, so they work unchanged.
  - Left: no hand-built `Payload(hashMapOf(…))` push (28), and 1 `routeParams[…]` read (19), the Inbox's generic path builder.
- **Roots.** `Root { HomeTabs(…) }` makes a container its graph's root where it is created, and `addRoot(route)` no longer takes a payload. BestBuds has no `addRoot` call left; Hangar has 2 and Manna 3, each choosing its first screen from a table or a field.
- **Graph-level navigation.** `replaceWith(route)`, `popToRoot()` and `currentRoute` replace `dispatch(Replace(sentinel.edge(route), Payload()))`, `while (backStack… > 1) dispatch(Pop)` and `backStack.entries.value.lastOrNull()?.edge?.end` in BestBuds, Hangar and Manna.
- **Port keys.** The app writes no port key (56 written and 73 empty before). A consumer whose key matches nothing takes the only provider of its type in the nearest scope (`findOnlyProvider`), and a tie fails with a message naming both providers.
- **One verb for what a node needs.**
  - `consumes<T>()` takes a port, `consumes(MeApi())` a service client and `consumes(MatchingApi().enqueue)` one operation.
  - This replaces `uses(…)` and `api(…)`, and with them the `import … api as serviceApi` alias in 20 files.
  - The kernel's source facts now recognise `consumes(Service())`.
- **Providing one value** is one line, `provide(AppEnvironment.current)`.
- **Hangar's 11 pane graphs** are one table over `WorkbenchMode`. One `WorkbenchPaneGraph(parent, mode)`, one `WorkbenchPaneBinding(mode)` and one `WorkbenchPaneScreen` replace 11 classes of each.
- **Start-up decisions.** Circles already picks its first screen in a node, `CirclesStart`; the `BestBudsApp.kt:236-241` reference was stale.

**Left:**
- **The payload type parameter.** `RouteBinding<Payload>(Payload())` is written at 43 sites, because `RouteBinding`, `RouteNode` and `NavigationEdge` carry a payload type that is always `Payload`. Removing it changes Studio and blueprint code that other sessions are editing, so it waits for them.

## 5. Hangar state keyed by strings

- **Tabs are strings.** The telemetry and agent panes keep tabs as strings: `"logs"`, `"spans"`, `"promql"`, `"crashes"`, and so on.
  - Each tab's behaviour is spread over separate `when (tab)` blocks: whether it has a subject, its data-freshness label, its sections, inspector and workspace, and the aliases that map one tab to another (`DomainPanes.kt:120-441`).
  - Views are strings too: `"configs" -> machine.agentsView = "configuration"` (`:73`).
- **Why it matters.** A typo falls silently into `else`, and a new tab needs five or six edits.
- **The change.** A sealed `TelemetryTab` (and `AgentTab`) whose members carry their own title, sections, freshness, inspector and workspace.
- **Scale.** The engine has 520 string `when` branches overall. Parsers of what a person typed, such as `SearchQuery.kt`, are fine as they are.

## 6. Parallel implementations to merge (quick wins)

- **compose-flow.** reaktor-blueprint uses only compose-flow's viewport, about 800 lines.
  - That is the `Viewport` model, the viewport half of `ReactFlowState` (`GraphCanvasState.kt:45`) and the pan/zoom gestures.
  - The node-and-edge editor around it, about 2,200 lines, has no users: a React Flow clone with its own `ReactFlow` composable, edges, nodes, handles, minimap and connection controller.
  - Move the viewport into blueprint and delete the module. The one-graph-style rule already says every map draws through blueprint.
- **SQL.**
  - reaktor-db's typed `SqlBuilder` (`reaktor-db/.../sql/sql.kt:208`) has no users outside reaktor-db.
  - The workers use reaktor-cloudflare's template `SqlBuilder` (`Sql.kt:74`) and also write SQL as plain strings: `SocialWorkerRepository.kt` alone has 34 multi-line SQL strings.
  - JVM services use Exposed through reaktor-db's `CrudRepository`.
  - The unused builder was deleted on 25 October. Next: move the workers' plain SQL into the template builder with typed rows.
- **Backoff.** Done on 25 October: one `Backoff` value replaces the hand-written loops.
- **JSON configurations.** 39 `Json { … }` instances are left (77), all combinations of five flags. The shared configs in reaktor-core cover the rest.
- **Breakpoints and text roles.** `ScreenClass` is declared three times (reaktor-surface-compose, and twice in reaktor-ui), `WindowSize` twice (reaktor-graph and reaktor-ui), and `TextRole` three times.
- **Task models.** `TaskPlan` and `TaskKind` exist in both reaktor-conductor and reaktor-tooling.
- **Small helpers written again.**
  - Base64: the hand-written `decodeBase64` functions are gone. Two `external` declarations of the browser's `atob` and `btoa` remain.
  - Byte conversion: done on 25 October, except reaktor-crypto's own copy (reaktor-crypto does not depend on reaktor-core).
  - Relative time: `ago` 8 times. Three Hangar copies just wrap `HangarPresentation.age`, the kernel has three copies, and the app has one.
  - `counted` is identical in two Hangar files.
- **Clock ticks.** Done on 25 October: `rememberNow(every)` and `rememberToday()` (`Time.kt`).
- **Push payloads.**
  - Notification data is encoded and decoded by hand as a flat string map (`NotificationPayloads.kt:229-244`), with fallbacks such as `data["reaktor_title"] ?: data["title"]`.
  - A `@Serializable` payload class, using kotlinx.serialization's `Properties` format for flat maps, handles both directions from one declaration.
  - `@SerialName` keeps today's keys for installed apps.

## 7. Re-parsing what the compiler knows

- **The kernel's regex parser.** `KernelReaktorFacts.kt` sits beside the compiler-backed atlas. Its service patterns still match a DSL that no longer exists (`:454`).
- **Hangar Studio.** It finds functions, `@Composable` slots and literal types with regexes over Kotlin source (`StudioStructure.kt:67, 434, 755, 822-830`), even though the atlas resolves declarations and `change_plan` rewrites Kotlin structurally.
- **The change.** Answer these questions from the atlas, and write changes through the structural change engine.
- **Lower priority.** `BestBudsSchemaCatalog.kt` learns the database schema by parsing SQL migrations with regexes.

## 8. Instrumentation behind import aliases (done 25 October)

- No file imports an instrumented function under a framework name; calls name the wrapper (`userPathLaunch { }`, `UserPathEffect(…)`, `observedClickable { }`, `signalBackground(…)`).
- Left, on purpose: a few plain `launch` and `LaunchedEffect` calls in the analytics pipeline itself, which must not trace itself, in DevTools, in navigation dispatch, and in the design module, which cannot depend on the app.
- Still possible: a new file can call plain `launch` and record no user path. Instrumenting reaktor's node-level `launch` would close that.

## 9. Swallowed failures

`runCatching { … }.getOrNull()` appears 316 times in BestBuds main code (Hangar engine 140, kernel 124, app 43) and 211 times in reaktor, counted as whole expressions on 27 October (376 and 219 on 24 October).
- Store reads: `ObjectState.getOrNull()` replaces the pattern where the state already exists. Reads that first open the store (`KV.state(…)`) keep `runCatching`, because the store can be missing early in start-up and in tests.
- The app's 43 are now mostly parsing (JSON, dates, Base64) and store opens. The boundaries left are listed in §3's next steps.
- That is fine for parsing.
- At service and device boundaries it hides outages, as in §3.
- Use `outcome {}` there.

## 10. The largest functions and files

- **Functions.** 80 are longer than 120 lines. The largest:
  - `ChatView`, 660 lines, split on 25 October into a `ChatRoom` state holder and five section composables;
  - Studio's `compose`, 575 (`StudioStructure.kt:231`);
  - `ReaktorWorkbenchContent`, 518 (`Workbench.kt:98`);
  - `DataWorkspaceSections`, 438;
  - the conductor's `run`, 351 (`Conductor.kt:33`);
  - `hybridConnectorMcp`, 335;
  - `workspaceOperations`, 268 (`KernelOperations.kt:352`), which declares every kernel operation inside one function.
- **Files.** 31 are over 800 lines. The largest are `StudioModel.kt` (2,016), `KernelAtlas.kt` (1,996), `OperationalControlPlane.kt` (1,845) and `BestBudsOperationalCatalog.kt` (1,758).
- **`!!`.** None is left in the app or in reaktor's runtime modules. What is left:
  - reaktor: flexbuffer (60), blueprint (17), dependeasy (15), board (6), conductor (6), tooling (5) and code (3).
  - BestBuds: the Hangar engine (58) and the kernel (16).
  - The 142 in reaktor-ui are in 71 vendored AndroidX icon files, which stay as Google wrote them.

## 11. Dead weight

- **`bestbuds/agent/`.** It is tracked audit material: reports, plus 20 `before/` copies of app screens, about 15,000 lines of Kotlin that no build compiles. They also show up in every search. Move them to the docs site or delete them.
- **compose-flow's editor** (§6).

## Suggested order

1. **App services: the rest of §3.** Message finish on the queue channel, `ChatUpdateAccepted` as `Unit`, and the last boundaries.
2. **Navigation** (§4). Typed payloads next, because they remove the most hand-written lines per change.
3. **Quick merges, at any time** (§6, §11). Each is a small change:
   - delete compose-flow's editor and `bestbuds/agent`;
   - the remaining `Json { }` configs;
   - one `ago`.
4. **One contract model for the tooling** (§1, §2), in stages:
   - merge `SafetyClass` into `Effect`;
   - kernel operations become services with typed outputs;
   - then the conductor's tools;
   - then DevTools commands.
5. **Hangar** (§5, §7). Typed tabs, and Studio working through the atlas.
6. **Hygiene** (§8-§10).
   - Instrument at the source.
   - Use `outcome {}` at boundaries.
   - Split the largest functions.
   - Remove `!!`.
