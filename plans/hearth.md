# Hearth — design & implementation

*Working name · podcast player · October 2026 · plan doc for [app-portfolio.md](app-portfolio.md)
idea 3. Rename before it ships — see §12, the App Store already has eight apps called Hearth-something.*

Subscribe to podcasts, play them, keep them for the train, and pick up on the tablet at the second
you stopped on the phone. A player in the plain sense: a library, an episode list, a queue, a speed
dial and a download button.

The app is the excuse. What it is for is four things the framework cannot do today: keep something
alive across every screen, play audio the way the OS expects a player to, move large files in the
background, and sync one number per record without a whole-database snapshot.

---

## 1. Why this stands alone

This portfolio killed a ten-idea draft for "features wearing app costumes", so the test comes first
and it is run against what exists now, not what existed in August.

| Piece | Already owned by |
|---|---|
| A session that outlives the screen | **Nobody, properly** — Lector, GymBuddy and Cairn each hand-roll one (§4.1) |
| Lock-screen transport on iOS | Lector, for speech (`lector@59aea90`) — reused here, §4.2 |
| Last-write-wins on one scalar per record | Lector's `ReadingPosition` has the same shape, unsynced (lector.md §5) |
| Audio-file playback, any platform | Nobody — `reaktor-media` has camera, gallery, speech and images, no player |
| Large background downloads | Nobody — `reaktor-io` downloads nothing; `reaktor-work`'s `PrefetchWorker` logs and returns |
| Sync transport, op log, reconnect | Nobody — `SyncAdapter` is a whole-file snapshot; `SyncWorker` logs and returns |
| XML | Nobody — reaktor has no parser |

**The honest result: it passes, narrowly, and not for the reason the portfolio gave.**

Two of its four forces are shared with Lector. Lector's reading session must also survive
navigation, and Lector's reading position is the same last-write-wins scalar. Had Lector been
built on a graph, it could have carried both, and Hearth would be a feature.

It was not. Lector builds its reader inside the Activity and keeps it alive by declaring every
`configChanges` it can name; on iOS a new document rebuilds the reader through a SwiftUI `.id(...)`.
That is a workaround for the easy version of the problem. Hearth owns the hard version, which
nothing else in the portfolio reaches:

1. **A process the OS starts without a screen.** A headphone button, a Bluetooth car, Android's
   playback resumption — each can start the app's playback service with no Activity alive. The
   long-lived scope must be buildable from that entry point, and today it cannot be: both Android
   storage adapters demand an `Activity` (§4.1). Lector's speech never hits this; Lector has no
   Android media session at all.
2. **Audio-file playback with a system media session** — Media3 on Android, AVPlayer on iOS, both
   driven from the lock screen, the notification shade, headphones and the car.
3. **Background transfer of 50–150 MB files** that survives the app being killed.
4. **The first sync transport**, which Money manager and Kitchen extend (§4.5).

The weak point is the habit, not the forces. A podcast player is a commodity and one is already
installed on every phone. If Hearth does not replace it, sync never sees two real devices and the
app becomes a demo. Two mitigations, both v1: OPML import on day one so switching costs one file,
and a gate before Phase 2 — Hearth is the only podcast player in use for two weeks first (§7).

---

## 2. Scope

**In, v1:**

- Android and iOS. One Compose Multiplatform graph.
- Subscribe by feed URL, OPML import, and search through the Apple Podcasts Search API
  (`itunes.apple.com/search?media=podcast` — free, no key, returns `feedUrl`).
- Stream or download an episode. Playback speed. A queue. Mark played.
- Lock-screen, notification-shade, headphone and car controls on both platforms.
- Position saved locally; offline listening from downloads.
- Downloads: resume after interruption, a storage limit, delete after played.
- Phase 2, gated (§7): position, played state and subscriptions synced across devices.

**Out, v1, and where each goes:**

- Desktop and web — later. Cairn already proved those shells; the player and the downloads are
  interface-plus-slot capabilities (§4.2, §4.3), so those shells get a slot that is empty until written.
- Video, transcripts, chapters, recommendations — out.
- Podcasting 2.0 tags beyond `<podcast:guid>` — out; parsed fields are additive later.
- CarPlay / Android Auto browse trees — out. Car *transport* (play, pause, skip) comes free with
  the media session; a browsable library in the car is separate work.
- Syncing the queue — out of tier 1. An ordered list is not one scalar; it waits for tier 2.

---

## 3. Product surface

Four screens and one thing that is always there.

- **Library** — subscribed shows as a grid of artwork, newest-episode first. Add by search, URL or
  OPML from here.
- **Show** — one podcast's episodes, newest first, each with its state: new, in progress (how much
  left), downloaded, played.
- **Episode** — notes, the download button, play.
- **Queue** — Up Next, reorderable.
- **The bar** — a mini player pinned under every screen while anything is loaded. Tap it for the
  full player: artwork, scrubber, skip back 15 s, play/pause, skip forward 30 s, speed.

The bar is the product's whole claim in one component: it exists on top of whatever route is in
front and it never restarts. If the bar ever flickers during navigation, the scope design failed.

---

## 4. Architecture

### 4.1 The long-lived scope — the main framework problem

**What exists, read rather than assumed:**

- **Lector's `ReadAloudSession`** is a plain class constructed inside `PdfReader`, which is
  constructed in `LectorActivity.onCreate` on `lifecycleScope` (iOS: inside the
  `LectorViewController` factory on a fresh `MainScope()`). It survives navigation because Lector has
  one screen and no graph. It survives rotation only because the manifest lists every
  `configChanges`. On iOS, `NowPlaying` registers remote-command handlers every time the factory
  runs and never removes them. **A workaround inside one app.**
- **`OngoingActivities`** in `reaktor-notification` is an `expect object`: a process-global *sink*
  for a lock-screen surface, with replayed `responses`. It holds no state and owns no lifetime;
  GymBuddy's `WorkoutInteractor` publishes into it. **A platform surface, not a scope.**
- **GymBuddy** makes its root graph a Kotlin `object`, built lazily on first composition, so it
  lives as long as the process. Its Activity installs adapters with `this` and then guards against
  doing it twice (`ensureKoinDependency()`, `if (Database == null)`).
- **Cairn** holds its graph in `private object CairnApp`, built from the first Activity, and says in
  its own comment: *"Process death is a different problem and is not solved here … Podcast hits the
  harder version of this."*

Three apps, three hand-rolled holders, and all three pass an `Activity` into something that lives
longer than it. That is not an accident of style: `AndroidFileAdapter(activity: Activity)` and
`AndroidSqlAdapter(activity: ComponentActivity)` accept nothing else. And because `Adapter` holds
its controller in a `WeakRef`, the result is not a leak but something quieter: once that first
Activity is collected, anything the adapter does through its controller returns null and does
nothing. **No graph that owns storage can be built from an `Application` or a `Service` today.**
That is the blocker for a player, because Android starts `MediaSessionService` on its own.

**The general version, into reaktor:**

1. **Storage adapters take a `Context`** and keep its `applicationContext`. Source-compatible —
   every caller passes an Activity, which is a Context — and it stops three apps holding their first
   Activity weakly inside a process-lifetime graph. (`reaktor-io`, `reaktor-db`.)
2. **`ProcessGraph<G>` in `reaktor-graph`** — the holder all three apps wrote: built once, by
   whichever entry point asks first, thread-safe, closeable. It replaces `object GymBuddy`,
   `CairnApp` and Lector's activity-scoped reader, none of which needs to change for this to land.
3. **The rule that makes it a scope rather than a global**, written into the `reaktor-app` skill:
   long-lived nodes live in a **session graph** with no routes; every UI graph is its child. Ports
   resolve upward only — a child's DI scope chains to its parent, never the reverse — so a screen can
   consume the player and the player can never hold a screen. A session graph is built before any
   child's `autoWire()`, which `ProcessGraph` guarantees by construction when the child's builder
   calls the parent's `get()`.

```
HearthSession   (ProcessGraph, no routes; built from Application, Service or Activity — first wins)
├── LibraryRepository   podcasts + episodes in ObjectDatabase          exposePort("library")
├── FeedService         fetch → parse → upsert                         exposePort("feeds")
├── PlaybackNode        owns Feature.AudioPlayer; queue; saves position exposePort("playback")
├── DownloadNode        owns Feature.Downloads; quota; delete-played   exposePort("downloads")
└── SyncNode            Phase 2 only
     │
     └── HearthUi       (ProcessGraph, child of the session — the routes)
         ├── LibraryScreen / ShowScreen / EpisodeScreen / QueueScreen
         └── consumes<Playback>("playback") — by key, because it crosses a graph
```

The mini-player bar is drawn by the app's root composable *around* `GraphContent(ui)`, so it is
outside every route by construction, and it reads the session directly.

**What process death costs.** The session graph is rebuilt from scratch in a new process. The
player comes back empty; `PlaybackNode` restores the last item and position from the database and
waits paused. Media3's playback resumption (`onPlaybackResumption`) asks for exactly that item when
the system offers "resume" on the lock screen. Phase 1.

### 4.2 Playback — a new module, `reaktor-playback`

**Why a new module and not a package in `reaktor-media`.** The `reaktor-module` test is what each
side would force on the other:

- `reaktor-media`'s manifest merges `RECORD_AUDIO` and pulls in CameraX and WorkManager. A podcast
  player depending on it would ship a microphone permission it cannot explain on its listing.
- A player merges `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, an exported
  `MediaSessionService`, and Media3's ExoPlayer and session libraries — the largest dependency in
  the app. Lector and GymBuddy depend on `reaktor-media` and want none of it.

Both directions fail, so it is a module. Shape: **interface plus slot** (absence is normal — no
player on desktop or web yet):

```kotlin
interface AudioPlayer {
    val state: StateFlow<PlayerState>      // item, playing, buffering, position, duration, speed, error
    val events: Flow<PlayerEvent>          // Ended(itemId), Failed(itemId, reason)
    fun load(item: MediaItem, startAtMs: Long = 0, play: Boolean = true)
    fun play(); fun pause(); fun seekTo(ms: Long); fun skip(byMs: Long)
    fun setSpeed(speed: Float)
    fun stop()
}
var Feature.AudioPlayer by CreateSlot<AudioPlayer>()
```

The common surface carries no platform type — a `MediaItem` is strings and longs.

**Android — Media3.** `AndroidAudioPlayer(context)` owns an `ExoPlayer` (speech audio attributes,
`handleAudioBecomingNoisy`, so unplugging headphones pauses) and a `MediaSession`. The module ships
`ReaktorPlaybackService : MediaSessionService` in its own manifest; `onGetSession` returns the
player's session. The node owns the player, not the service — the service is only how Android is
told a player exists. Binding a `MediaController` when playback starts keeps the service bound, and
Media3 promotes it to foreground while playing. Podcasts skip rather than change track, so the
session's player is a `ForwardingPlayer` that turns next/previous into +30 s / −15 s: a headphone
double-press and the car's next button both skip forward, as listeners expect. Media button
preferences put skip-back-15 and skip-forward-30 on the system controls.

**iOS — AVPlayer.** `DarwinAudioPlayer` owns an `AVPlayer`, a playback `AVAudioSession` in
spoken-audio mode, `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`. What Lector learned and
what is reused:

| From `lector@59aea90` | Kept | Changed |
|---|---|---|
| `UIBackgroundModes=audio` in Info.plist | yes | — |
| Playback category, spoken-audio mode, activated at start | yes | activated on first `load`, not at launch, so opening the app does not stop someone's music |
| Interruption pauses | yes | also resumes when the interruption ends with `ShouldResume` — Lector only paused |
| Remote commands play / pause / toggle | yes | registered once per process, and removable — Lector re-registered on every factory call |
| Now playing: title + artist + rate 0/1 | — | adds duration, elapsed time and the real rate, without which the lock-screen scrubber is wrong |
| next / previous → reader commands | — | skip forward 30 / back 15 with `preferredIntervals`; change-position (scrub) and change-rate |
| — | — | route change "old device unavailable" (headphones out) pauses |

Elapsed time is published on state changes and seeks, not every tick: the lock screen extrapolates
from elapsed and rate on its own.

### 4.3 Background downloads — into `reaktor-io`

Neither existing shape carries a 100 MB file:

- **`reaktor-work`'s `PrefetchWorker`** runs on meeseeks — WorkManager on Android, `BGTaskScheduler`
  on iOS. A BG task is given seconds to a few minutes, when the OS chooses. A podcast episode does
  not fit. On Android a worker over ten minutes needs its own foreground service.
- **`reaktor-io`'s Ktor client** dies with the process.

What does carry it, on both sides, is a transfer the **OS** owns: `DownloadManager` on Android and a
background `URLSession` on iOS. Both survive the app being killed, both resume after a dropped
connection where the server allows ranges, both follow the tracking redirects podcast enclosures
are wrapped in (every feed sampled here goes through one to five: podtrac, Spotify's prefix,
swap.fm). Chosen over WorkManager for symmetry: one model, "hand the OS a URL and a destination,
reconcile on launch", on both platforms.

```kotlin
interface BackgroundDownloads {
    val transfers: StateFlow<Map<String, Transfer>>   // by caller's key: queued, running(bytes), done(path), failed(reason)
    fun enqueue(key: String, url: String, fileName: String, title: String, allowMetered: Boolean)
    fun cancel(key: String)
    suspend fun reconcile()                            // after launch: what finished while we were dead
}
var Feature.Downloads by CreateSlot<BackgroundDownloads>()
```

No new permission (`INTERNET` is the app's), no SDK — so it is a file in `reaktor-io`, not a module.
iOS needs one line of Swift: the app delegate hands
`application(_:handleEventsForBackgroundURLSession:completionHandler:)` to the adapter.

**Policy stays in the app** (`DownloadNode`): a storage cap (default 2 GB; when full, the oldest
*played* download goes first, never an unplayed one), delete-after-played (on by default, 24 h after
marked played, so an accidental "played" is recoverable), Wi-Fi only (default on).

### 4.4 Feeds and the XML decision

Measured on real feeds (October 2026): NPR *Up First* is 2.0 MB with 500 items; *The Daily* is
**20.5 MB with 3,014 items**. All seven sampled — NPR, Simplecast, Art19, Anchor, Buzzsprout,
Megaphone and a self-hosted WordPress feed — were well-formed XML.

That settles one thing and leaves another open. A DOM of a 20 MB feed is well over 100 MB of
objects on a phone, so **the reader must stream**. Whether it must also be *tolerant* depends on the
long tail of self-hosted feeds the sample did not reach: HTML named entities (`&rsquo;`, `&nbsp;`)
outside CDATA are fatal to a strict parser and are what a WordPress feed emits.

| Option | Streams | Tolerant | Cost to other apps | Notes |
|---|---|---|---|---|
| A. `xmlutil` core (`KtXmlReader`) | yes | relaxed mode, kxml2-style | a dependency for every `reaktor-io` consumer, or a module for it | mature; the obvious pick if B misparses |
| **B. Own pull reader in `reaktor-io`** | yes | yes — unknown entities kept literal, HTML named set decoded | none — no dependency | ~300 lines of common Kotlin, no regex (Kotlin/JS regex traps) |
| C. Platform parsers (`XmlPullParser`, `NSXMLParser`) | yes | no | none | two implementations to keep equal; JVM/JS need a third |
| D. Ksoup | no (DOM) | yes | heavy | ruled out by the 20 MB feed |

**Default: B**, tested in Phase 0 against the seven real feeds plus hand-broken fixtures. Switch to A
if B misreads anything a strict parser reads correctly. The RSS mapping — `<item>`, `<enclosure>`,
`itunes:*`, `<podcast:guid>` — and OPML stay in the app until a second consumer appears (Cairn II's
URL fetch is the likely one).

**Episode identity** is `(feedUrl, guid)`, falling back to the enclosure URL when a feed has no
`<guid>`. GUIDs are not unique across feeds; the pair is. This key is what sync carries, so it is
fixed in Phase 0, not later.

### 4.5 Sync, tier 1 — designed here, built only after the gate

Build on `ObjectDatabase`'s change feed. **Not** `SyncAdapter`, which uploads and restores the whole
database. `SyncWorker` is a shell that logs; it is not a starting point.

```
local put (Origin.Local) on a synced store
   → Outbox: append (store, key, value, hlc, device)    // persisted store, survives process death
   → Transport.push(batch) when connected                // acked ops removed from the outbox
remote ops ← Transport.pull(since = cursor)
   → for each: if op.hlc > local.hlc → put(Origin.Sync)  // last-write-wins per key
   → cursor advanced only after the batch is written
```

Three pieces, each built so tier 2 extends rather than replaces it:

- **Op log (outbox).** A persisted store, not the in-memory `events` flow — a `MutableSharedFlow`
  with nobody collecting drops what it emits, and a process killed between a write and a push would
  lose the write. Ops written with `Origin.Sync` are never re-queued, which is what stops echo.
  Tier 2 keeps the log and adds per-field ops.
- **Clock.** A hybrid logical clock (wall time plus a counter plus device id), not `updatedAt`. Two
  phones a minute apart in clock skew would otherwise let the stale one win every race.
- **Transport + reconnect.** A `SyncTransport` interface: `push(ops)`, `pull(since)`. First
  implementation is HTTP to a Worker. Reconnect is the supervising loop the `reaktor-app` skill
  already prescribes for sockets: retry forever with backoff, resend everything un-acked on every
  reconnect, never trust "sent" as "delivered".

What syncs in tier 1: playback position and played state per episode, and the subscription set
(add/remove with tombstones). What does not: the queue, downloads (device-local by nature), settings.

**Decisions that are the owner's, not this plan's (asked before Phase 2):**

- **Identity.** (a) An account through `reaktor-auth` — Google and Apple sign-in already exist on
  Android, iOS and web, JWT verified in the Worker; recovery is "sign in again". (b) Device pairing
  like Cairn — no account, a pairing code; lose every device and the sync space is gone (local data
  stays). Cairn's pairing is app code today; choosing (b) means promoting it into reaktor first.
- **Infrastructure.** Tier 1 needs a Worker and storage, not a Durable Object: positions are pulled
  on open and pushed on change, and a scalar does not need live fan-out. Worker + D1 on the free
  plan covers one person by orders of magnitude. A Durable Object buys live handoff — tablet jumps
  the moment the phone pauses — and the Workers paid plan once past free limits. No R2: nothing
  binary syncs. Verify current Cloudflare limits at decision time.

### 4.6 Modules

| Module | Role | New or reuse |
|---|---|---|
| `reaktor-graph` | `ProcessGraph`; session graph → UI graph | **new file** |
| `reaktor-io` / `reaktor-db` | storage adapters take a `Context` | **change** |
| `reaktor-playback` | `AudioPlayer`: Media3 + `MediaSessionService`, AVPlayer + now-playing + remote commands | **new module** |
| `reaktor-io` | `BackgroundDownloads` (DownloadManager, background URLSession); XML pull reader | **new files** |
| `reaktor-db` | `ObjectDatabase` stores; Phase 2: outbox, HLC, `SyncTransport` | reuse; Phase 2 new |
| `reaktor-ui` | theme, reduce motion | reuse |
| `reaktor-cloudflare` | sync Worker | Phase 2 only |
| `reaktor-auth` | identity, if chosen | Phase 2 only |

---

## 5. Data model

`ObjectDatabase`, one store per kind. Rows hold metadata only — the `reaktor-app` skill's 2 MB
`CursorWindow` limit means artwork and audio are files, never rows.

```
Podcast(feedUrl, title, author, artworkUrl, description, subscribedAt, refreshedAt, etag?, lastModified?)
Episode(key = feedUrl|guid, feedUrl, guid, title, publishedAt, durationMs?, enclosureUrl,
        enclosureBytes?, enclosureType, notes)
Progress(episodeKey, positionMs, durationMs, played, playedAt?, updatedAt)   // the synced scalar
Download(episodeKey, state, path?, bytes, totalBytes?, finishedAt?)          // device-local
Queue(order: List<episodeKey>)                                               // one row, device-local in v1
Settings(speed, skipBackS = 15, skipForwardS = 30, wifiOnly = true, storageCapMb = 2048,
         deleteAfterPlayed = true)
```

`Progress` is kept apart from `Episode` on purpose. A feed refresh rewrites episodes wholesale; it
must never touch where someone is in one. And `Progress` is what syncs — a separate store means the
outbox watches exactly one store's events.

Position is written on pause, on seek, on item change, and every 15 s while playing — not every
tick. A crash loses at most 15 s.

---

## 6. Phases

**Phase 0 — spike.** One hard-coded feed (NPR *Up First*) → stream it through the XML reader → list
the episodes → play one, on Android and iOS. Two screens with navigation between them, the mini-player
bar over both. Lock-screen controls on both platforms. Playback continues across navigation and with
the app in the background. Proves: `ProcessGraph`, the Context adapters, `reaktor-playback` on both
platforms, the XML reader against real feeds. No library, no downloads, no sync.

**Phase 1 — an offline app.** Library (subscribe by URL, OPML import, Apple search), show and episode
screens, queue, speed, mark played, downloads with resume, cap and delete-after-played, position in
`ObjectDatabase`, playback resumption after process death, the `DESIGN.md` and house-style pass.
*Checkpoint:* the only podcast player on one phone for two weeks.

**Gate.** Stop. Ask the owner the two §4.5 questions. Phase 2 does not start on a default.

**Phase 2 — sync tier 1.** Outbox, HLC, `SyncTransport`, the Worker, the identity choice.
*Checkpoint:* pause on the phone, open the tablet, it is at the same second.

**Phase 3 — reach.** Desktop and web shells (players for both slots), CarPlay / Android Auto browse,
chapters.

---

## 7. Copied by hand — the input for `reaktor-cli new`

`reaktor-cli` has no `new` command (portfolio §9). Everything below was copied or retyped from Lector
to stand Hearth up; this list is the template's specification. Updated as Phase 0 lands.

| # | File | What was copied, and what had to change by hand |
|---|---|---|
| 1 | `settings.gradle.kts` | Lector's whole: plugin repositories, plugin versions read from `gradle.properties`, `includeBuild("../reaktor/dependeasy")`, `includeBuild("../reaktor")` with a `dependencySubstitution` list of every reaktor module. **The list is hand-kept and had drifted** — Lector's lacks `reaktor-tactile`, `reaktor-pdf`; Hearth added those and `reaktor-playback`. The template should generate it from reaktor's own `settings.gradle.kts`. |
| 2 | `build.gradle.kts` | Verbatim: plugins `apply false`, repositories, `-Xexpect-actual-classes`, lint off, iOS release linking skipped. |
| 3 | `gradle.properties` | Verbatim. AGP, Compose, Kotlin, KSP, Ktor, SQLDelight and `reaktorVersion` duplicated from reaktor by hand — a version bump in reaktor is a silent mismatch here. |
| 4 | Gradle wrapper | `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (Gradle 8.14.2). |
| 5 | `.gitignore` | Verbatim (Pods, `*.xcworkspace`, `*.podspec`, `.github_modules`, build dirs, signing patterns). |
| 6 | `local.properties` | `sdk.dir` and the CocoaPods binary path. Per machine, not committed. |
| 7 | `modules/app/build.gradle.kts` | Shape from Lector; dependencies chosen per app. The darwin `podDependencies` line linking `sqlite3` is required by anything on `reaktor-db` and is easy to forget (link-time failure). |
| 8 | `modules/app/src/androidMain/AndroidManifest.xml` | App-level permissions only. |
| 9 | `targets/appAndroid/build.gradle.kts` | Verbatim except the application id. |
| 10 | `targets/appAndroid/src/main/AndroidManifest.xml` | Application class (new for Hearth: the session is built in `Application.onCreate`), the launcher activity. |
| 11 | `targets/appAndroid/src/main/res/values{,-night}/themes.xml` | NoActionBar theme with the window ground colour — which must match the app's palette by hand (CO9). |
| 12 | `targets/appDarwin/` | `Podfile`; `regenerate-xcodeproj.rb` (bundle id edited, Lector's sample PDF removed); `iosApp/iOSApp.swift`; `Info.plist` (display name, `UIBackgroundModes`); `Assets.xcassets` with `LaunchBackground` and `AccentColor` colour sets matching the palette. `pod install` needs `LANG=en_US.UTF-8` on this machine. |
| 13 | Entry points | `HearthApplication`, `HearthActivity`, `HearthViewController.kt`: the adapter install order (Dependency, File, Sql, Database, then capabilities), and the `ProcessGraph` pair. |
| 14 | Design starter | `design/Theme.kt`, `Tokens.kt`, `Motion.kt` (with the house press), `Components.kt`, `Format.kt`, the contrast test, `DESIGN.md` — shape from Lector and GymBuddy, values new. |
| 15 | First configure | dependeasy clones `hermes` and `flatbuffers` into `.github_modules/` of every new app on its first Gradle run. Nothing in Hearth uses either. |

What the template should take from this: items 1, 3 and 11–12 are the ones that drift silently; items
13–14 are the ones that differ per app but share a shape.

---

## 8. Reuse contract

Stated so nothing here is re-costed later:

- **iOS background audio and remote commands** — Lector's approach, generalised and moved into the
  framework (§4.2). Lector can switch to `reaktor-playback`'s session handling later; nothing forces it.
- **The process scope** — `ProcessGraph` and the session-graph rule are framework, available to Lector,
  GymBuddy and Cairn. None of them is migrated by this work.
- **Storage** — `ObjectDatabase` as is. **Reconnect** — the `reaktor-app` socket rules, applied to
  HTTP.

---

## 9. Feasibility

**High for Phases 0–1, medium for Phase 2.** Media3 and AVPlayer are the platforms' own answers;
DownloadManager and background `URLSession` are too. Nothing here needs a model, a server or money
until the gate.

Where the risk is:

1. **iOS remote commands from Kotlin/Native** — selector-overloaded delegate protocols
   (`NSURLSessionDownloadDelegate`) and `CMTime` structs crossing the bridge. Known pain, bounded.
2. **Android service start rules** — a foreground service started from the background is refused on
   Android 12+. Starting playback from the UI is fine; resumption from a headphone button goes through
   Media3's own path. Tested in Phase 1, not assumed.
3. **Feed tail** — tolerant parsing is the bet in §4.4; the fallback is named.

---

## 10. Open decisions

| # | Decision | Options | Default if unresolved | Needed by |
|---|---|---|---|---|
| 1 | XML reader | own tolerant pull reader / xmlutil / platform / Ksoup | **taken: own reader in `reaktor-io`** (§4.4, §13) | Phase 0 |
| 2 | Android downloads | DownloadManager / WorkManager + Ktor ranges | DownloadManager | Phase 1 |
| 3 | Skip intervals | 15/30, 10/30, configurable | 15 back, 30 forward, configurable in Phase 1 | Phase 0 |
| 4 | Sync identity | `reaktor-auth` account / Cairn-style pairing | **none — the owner decides** | Phase 2 |
| 5 | Sync infrastructure | Worker + D1 (free) / + Durable Object (paid plan) | **none — the owner decides** | Phase 2 |
| 6 | Name | Hearth / other | rename before store listing; bare "Hearth" is likely taken | before Phase 1 ships |

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| Hearth never replaces the installed player, so sync tests nothing | OPML import day one; two-week sole-player gate before Phase 2 |
| The session scope leaks a screen or a dead Activity | ports resolve upward only; adapters keep `applicationContext`; the bar flickering is the visible test |
| A foreground-service rule kills playback in the background on some Android version | Media3's own service and notification; test on API 26 and 36 |
| A feed the reader cannot parse | tolerant reader; the strict-library fallback is named (decision 1) |
| Downloads fill the phone | cap with oldest-played-first eviction; never evict unplayed |
| Sync clobbers a real position with a stale one | HLC rather than wall clock; a position only moves forward unless the user seeks back on that device |

---

## 12. The name

Checked 22 October 2026 against the App Store: *Hearth Companion App*, *Hearth for Contractors*,
*Hearth Community*, *Hearth: Cozy Fireplace*, *Hearth: Family Care*, *Hearth: Bills & Reminders*,
*Druid Hearth* and *myhht Hearth Expert*. None is a podcast player, but App Store names are unique and
"Hearth" alone is very likely taken. Play not checked. The package is `ai.hearth.app` until the name
is settled; the bundle id changes with it.

---

## 13. Status

**Phase 0 — done, 22 October 2026.** Framework: `3d191511` (Context adapters), `9a054c07`
(`ProcessGraph`), `525c3c0d` (`XmlReader`), `ff1fd2ba` (`reaktor-playback`), `012ae36e` (skill).
App: `hearth@1b83798`.

Decision 1 (XML) is taken: the own reader. It read all seven sampled feeds identically to the
JDK's strict StAX parser (`XmlCorpusTest`), the 20 MB one in 162 ms on the JVM, and its 17 tests
pass on JVM, JS and the iOS simulator.

What was checked, and where:

| Check | Android (emulator, API 36) | iOS (simulator, iOS 26.3) |
|---|---|---|
| Feed fetched and parsed on device | yes | yes |
| An episode streams and plays | yes | yes — CoreMedia log shows the audio queue running |
| Navigating show → episode → back leaves it playing, bar intact | yes | yes |
| Rotation (Activity recreated) leaves it playing, same process | yes | — (portrait only) |
| App in the background keeps playing | yes (HOME, screen off) | yes — buffers still enqueuing 20 s after HOME and through lock, 3 min in |
| Media session registered with the system | yes — `dumpsys media_session`: active, PLAYING, Back 15 / Forward 30 actions; service foreground as `mediaPlayback` | yes — `mediaremoted` logs Hearth as the active Now Playing app with its info and playing state |
| Lock-screen controls drive the player | **yes** — card shows title, show, art, scrubber; pause, +30, −15, play each change the session state | **not verified** — the simulator draws no lock-screen media card and an empty Control Center |
| Headphone buttons | **yes** — media keys via `cmd media_session dispatch`: pause, next = +30 s, previous = −15 s, headset hook = play | **not verified** — no way to send a remote command to the simulator |
| Real device | no | no |

The iOS remote-command path (lock screen, AirPods, CarPlay) is wired the way Lector's was and
registers with the system, but nobody has pressed a button on it yet. It needs one run on a real
iPhone before Phase 1 is called done. Whether iOS shows the skip buttons rather than track arrows
with next/previous disabled is part of that run.

**Phase 1 — built, 3 November 2026; iOS walk-through pending.** Framework: `3a474019` (background
downloads, document picker), `22db2c31` (resumption, iOS artwork), `93a467e3` (`BackHandlerContainer`
honours its modifier), `27c2ffdd` (a failed item is not playing). App: `hearth@2336702`. 19 tests.

Checked on the Android emulator: Apple search, follow, OPML through the system chooser, download,
offline playback of the download, error and retry when streaming offline, Up Next moving on, restore
after a restart, dark theme override, and a media key resuming playback in a process the system
started for `ReaktorPlaybackService` alone. iOS: builds and launches to the empty library; nothing
past that was walked. Background downloads on iOS and the Swift event hook are untried.

Learned: the same show arrives under two addresses (Apple lists Darknet Diaries at
`podcast.darknetdiaries.com`, OPML exports have the Megaphone address); both feeds declare the same
`itunes:new-feed-url` and `podcast:guid`, so identity comes from the feed, and a followed show keeps
the address its positions are keyed by. A small case of Cairn II's "identity across moving sources".

Still to do before the gate: an iOS walk-through, a real-device run of iOS lock-screen and headphone
commands, an app icon. Then stop for decisions 4 and 5.

Related: [app-portfolio.md](app-portfolio.md) · [lector.md](lector.md) · [cairn.md](cairn.md)
