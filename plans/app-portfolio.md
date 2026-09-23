# Reaktor — App Portfolio & Framework Gaps

*Working document · revised September 2026 · supersedes the earlier ten-idea list — the tenth here earns its slot on force coverage, not habit (§4, idea 10)*

Apps chosen so that every hard problem in the framework gets exercised exactly once, plus the
framework gaps each one closes. The set is deliberately small: earlier drafts had ten ideas, most of
which were features wearing app costumes. What survives here are things with a spine — a reason to
open them regularly and a data model that gets more valuable the longer it runs.

This revision corrects four claims that did not survive a source check, reorders the sequence so the
riskiest architectural question is answered first, and adds the modules that landed after the
original draft and still have no consumer.

---

## 1. What's already claimed

Off the table — covered by existing products in the stack:

| Territory | Owned by |
|---|---|
| Graph editor, blueprint canvas, node inspector, DevTools, deploy topology | BestBuds Desktop |
| MCP server/client, agent orchestration | Manna (`manna-mcp` is the default JWT audience in `reaktor-auth`) |
| Knowledge maps, concept roadmaps, n8n-style executable flows | Manna |
| Education / course / student domain | Manna |
| Chat, messaging, social discovery, public events | BestBuds |
| Observability, Grafana, Pulumi IaC | `reaktor-cloud` |

---

## 2. Where the framework is actually thin

From the source tree, not the README — which is stale (still lists `notification` as Brainstorming
despite full Android/iOS implementations, and omits `reaktor-performance`, `reaktor-security`,
`reaktor-secrets`, `reaktor-service`, `reaktor-cli`, `reaktor-sensors` and `reaktor-health`
entirely).

**Nothing exists yet:**

- **Motion / interaction feedback.** `ripple` and `haptic` appear zero times in the Kotlin source.
  Every `spring` hit is Spring WebFlux, not spring physics. Animation and gesture code lives almost
  entirely inside `compose-flow` / `reaktor-flow` — trapped in the graph canvas, unavailable to app UI.
  `reaktor-tactile` has a build file, a podspec and a README, and no source files.
- **Localization.** `i18n` appears zero times. No strings, plurals, locale-aware dates/numbers/
  currency, no RTL.
- **Accessibility.** Semantics exist only in `reaktor-flow`, for graphs. No app-level focus order,
  screen-reader labels, dynamic type, contrast or reduced-motion.
- **Widgets / live activities.** No module at all (Android Glance, iOS WidgetKit).
- **App scaffolding.** `reaktor-cli` has lifecycle, topology, deploy, ops and docs commands but no
  `new` / template / scaffold command. Every app in this portfolio would hand-roll its KMP module
  layout, targets, podspec and webpack config. See §9.

**Exists but wrong, thin, or unproven:**

- **Sync.** `SyncAdapter` is whole-database snapshot upload/download — `sqlAdapter.backup()` → PUT →
  GET → restore. Two devices editing offline means one silently clobbers the other's entire database.
  **But the replacement has already started:** `ObjectDatabase` now emits a change feed —
  `DatabaseEvent.Put/Delete/Clear/Invalidated`, each tagged with
  `Origin { Local, External, Sync, Migration }` — and `reaktor-work` already ships a `SyncWorker`
  shape. The tier-1 substrate exists; what's missing is the transport, the merge and the reconnect
  behaviour on top of it.
- **Permissions.** *Corrected from the original draft, which said no unified surface exists.* It
  does: `reaktor-core/.../adapters/PermissionAdapter.kt` models
  `PermissionResult.Granted / Denied.Once / Denied.Forever`, carries a `Permission` constant set
  (camera, location, storage, gallery, speech, notifications), has Android and Darwin
  implementations, an `InMemoryPermissionAdapter` for tests, and a fully modelled notification
  permission path (`NotificationPermissionStatus`, provisional/ephemeral states, category blocks).
  The real gaps are narrower and all sit above the adapter: no rationale UI, no
  deep-link-to-settings on denied-forever, no Compose-level `rememberPermission`, and
  `request(vararg): Boolean` is all-or-nothing so partial grants need `requestOptional` by hand.
  Build the UI layer; do not rebuild the adapter.
- **Forms & validation** — logic scattered across ~20 files, no shared model.
- **Feature flags / remote config** — one hit in the entire repo.
- **Deep links** — routes exist; platform registration and cold-start routing don't.
- **`reaktor-media`** — camera and gallery are real on both platforms, but `speech/` is
  `commonMain` only: `SpeechRecognizer` and `SpeechSynthesizer` have no Android or Darwin
  implementation. No video. The recognizer half is forced by idea 7 (Recorder), the synthesizer half
  by idea 10 (voice reader).
- **`reaktor-security`** — *corrected:* this is not a generic crypto box, it is a full MLS stack
  with `mlspp` vendored in `cpp/external`, group conversations, key package publishing and member
  add/remove. What exercises MLS is **membership churn**, not two-party transfer. Zero consumers —
  Cairn is the first, and its *device set* turns out to be a genuine group whose membership changes
  on every install, reinstall and lost phone.
- **`reaktor-secrets`** — implemented, zero consumers.
- **`reaktor-web`** — real Android and Darwin WebView implementations, three source files, no known
  consumer.
- **`reaktor-health`** (Health Connect + HealthKit) and **`reaktor-sensors`** (CoreMotion step
  counter) both landed recently, both have tests, both have **zero consumers and no app in this
  portfolio that touches them**. Addressed by idea 6.
- **`ShareAdapter`** — Android share-sheet work in progress in `reaktor-io`, not yet committed, and
  **outbound only**: `shareFile(payload)` hands a file *to* the platform sheet. Receiving content the
  user shares *into* an app does not exist anywhere in the repo. An earlier draft counted this as
  reuse; it is new work. Design in [cairn.md §7](cairn.md).

---

## 3. Design note: equation-driven feedback

The tactile module should not ship a fixed set of effects. Feedback is a **parametric waveform with
multiple output channels**:

```
f(d, t) = A · sin(k·d − ω·t) · e^(−λ·t)
```

`d` is distance from the touch origin, `t` is time since the interaction. The same function drives:

- **pixels** — displacement/scale of the pressed element and its neighbours
- **the vibration motor** — amplitude envelope
- **audio** — optional click or tone

Consequences:

- Presets are **named coefficient sets**, not hardcoded effects. `press`, `select`, `success`,
  `error`, `dragStart` become token entries beside `DesignTokens`, so feel is themeable like colour.
- The wave needs **origin-aware propagation**: the touch point published down a subtree, elements
  responding as a function of distance and delay. That's the primitive worth owning.
- **Reduced-motion** is an input to the equation (damping → ∞), not a branch bolted on later.
- Much of the springs/easing/choreography work already exists inside `compose-flow`. Extracting it
  is both the cheapest path and a real test of whether `compose-flow` is as generic as claimed.

---

## 4. The portfolio

Ten ideas across nine apps — ideas 1 and 2 are two arcs of one app, Cairn. Six come from the
original draft (three rescoped), plus Cairn arc II, Steps & health, and the reader idea now folded
into Cairn. Idea 10, the voice reader, is added September 2026: it earns a slot despite this doc's
standing suspicion of a tenth idea because it owns three forces nothing else claims (its own costume
test spells them out) instead of wearing an app costume over borrowed ones. Apps with a design doc
link to it under the heading.

### 1. Cairn, arc I — device handoff
**Primary stress: multi-target parity + realtime + encryption.**
**Sequence position: first.**
**Design doc: [cairn.md](cairn.md)** — one app, two arcs; ideas 1 and 2 are its two halves.

Send a link, a file or a snippet from phone to laptop to browser and back.

Small product, deep reach. Durable Objects for the room and presence; binary transfer through R2;
end-to-end encryption as the first consumer `reaktor-security` has ever had. It only makes sense if
it genuinely runs on phone *and* desktop *and* web — which makes it the one-graph-three-shells
proof, with a daily reason to use it.

Ingestion is not free reuse: the `ShareAdapter` in flight is outbound only, so receiving shares is
new framework work — a `ShareReceiver` adapter with an Android intent filter, desktop drag/paste and
a Web Share Target.

On encryption, the design doc lands somewhere better than the previous draft assumed. Two devices
swapping a file is a thin use of MLS, but your *device set* is a group whose membership churns on
every install and lost phone, and forward secrecy across a device you no longer control is a real
requirement. Cairn is a fair first consumer of `reaktor-security`, not a token one.

*Forces:* multi-target parity · realtime + presence · encryption integration · binary transfer · notifications · share sheet
*Size:* 2–3 weeks

### 2. Cairn, arc II — every doc you have to get through
**Primary stress: ingestion from anywhere + identity across moving sources.**
**New — absorbs the read-later idea from the previous draft.**
**Design doc: [cairn.md](cairn.md)** — the same app as idea 1, second arc.

Drop any document in, from any device, at any time. It stays, it is searchable, and it tracks what
you still owe on it.

Not a separate product — the same graph, Worker, storage and shells as arc I, with four nodes added
and two fields filled that arc I already writes as null. Arc I moves a thing between devices and
forgets it; arc II keeps it and remembers what it means.
That makes it cheap in framework terms and the only app on this list with a daily need today:
~1,289 markdown files across the five working repos, of which about twenty generate real
obligations, currently tracked in a todo note that goes stale on every revision.

The unclaimed hard problem is **identity across moving sources** — the same document arriving
repeatedly by different routes under different names, and needing to stay one obligation thread.
That is not sync (replicas of your own writes); it is reconciliation of an external thing arriving
again. Nothing else in the portfolio touches it.

Also the only consumer of `reaktor-web`, and the first honest workload for `reaktor-work`, since
documents arrive whenever and extraction is a queue rather than a pass.

Ships in two parts: a retained, searchable, readable inbox first; obligation extraction second,
gated on the first having been used long enough to know what it needs.

*Forces:* multi-source ingestion · content-addressed identity + supersession · WebView · full-text search · background queue
*Absorbs:* read-later / offline reader
*Size:* ~3.5 weeks after arc I, plus ~3 weeks for extraction if taken

### 3. Podcast / audio player
**Primary stress: graph structure.**
**Sequence position: third — first app on the spine.**

Library, player, downloads, offline listening, position synced across devices.

Playback must survive every navigation, so the player cannot live in a route graph — it needs its
own long-lived scope, observed by whatever screen is on top. That is a direct assault on graph
scoping, lifecycle ordering and DI hierarchy, and it's the thing that's ugly in every other
framework. Brings background downloads, binary storage and permissions with it.

Sync here is the easy case — playback position is one scalar per episode where last-write-wins is
genuinely correct. That makes it the right place to build the first sync path, on top of the
`DatabaseEvent` / `Origin` change feed that already exists.

*Forces:* graph scoping · lifecycle · background downloads · media playback · simple sync
*Size:* 3–4 weeks

### 4. Money manager
**Primary stress: completeness — forms, i18n, hard sync.**
**Rescoped: automatic capture, not manual entry.**

Single-user, offline, years of records.

Currency and number formatting per locale is the canonical i18n test. Typed forms with validation
is the second-biggest missing framework layer after motion. Record-level sync — two devices editing
different transactions offline, both surviving — is where `SyncAdapter` gets replaced. And years of
transactions finally give `reaktor-performance` something honest to measure.

**The rescope matters.** Manual expense entry is abandoned in about three weeks, and an abandoned
app generates no offline edits, which means the record-level sync tier never gets tested by real
conflicts — the entire reason this app is on the list. Ship it with automatic capture: an Android
notification-listener parsing bank and card alerts, with manual entry as the correction path rather
than the primary one. That also drags notification-listener access and a parsing/rules layer into
the framework.

This app is also the designated **accessibility and localization reference implementation** — those
two gaps are cross-cutting and will otherwise never be anyone's job. Gate its release on them.

*Forces:* forms & validation · currency i18n · record-level sync · query performance · migration · a11y · notification listener
*Size:* 3–4 weeks

### 5. Kitchen — shopping list and pantry
**Primary stress: collaborative sync + measurement i18n.**
**Rescoped: cut to the sync core; recipes are phase 2.**

The household shares a live shopping list; the pantry knows what's already in the house; cooking
mode works with wet hands.

The shopping list is genuinely multi-user offline — two people in different aisles both editing —
which is the hardest sync tier. Unit conversion across metric, imperial and locale-specific volumes
is a nastier i18n problem than currency. Cooking mode is where eyes-free haptics earn their place.

**Precondition:** tier-3 sync only gets tested if a second real person edits the same list
concurrently. Confirm someone in the household will actually use it before starting; if not, this
app teaches nothing that the money manager didn't, and should be dropped rather than shipped
single-user.

**Phase 2, only after phase 1 ships:** recipe URL parsing in a worker, weekly meal plan,
barcode pantry scanning, receipt OCR.

*Forces:* multi-user conflict resolution · unit i18n · tactile (eyes-free) · binary attachments
*Absorbs:* shared checklist, kitchen timers
*Size:* 2–3 weeks phase 1, 2–3 weeks phase 2

### 6. Steps & health dashboard
**Primary stress: widgets + the two orphaned modules.**
**New — replaces Commute as widget owner.**

Steps, activity and sleep on a lock-screen widget, with the history that neither platform's own app
will let you keep.

`reaktor-health` (Health Connect + HealthKit) and `reaktor-sensors` (CoreMotion step counter) both
landed with tests and neither has a single consumer. This is the smallest app that gives both a
production workload. The widget is the primary surface, not an add-on — which forces an entirely
new module spanning Android Glance, iOS WidgetKit and live activities, plus background refresh
budgets, with no external data dependency to derail it.

*Forces:* widgets/live activities · health + sensor adapters · background budgets · permissions
*Size:* 1–2 weeks

### 7. Voice recorder with transcription
**Primary stress: media capture + search, then on-device compute.**
**Rescoped: split into two phases so FFI cannot eat the schedule.**

Record a meeting or a thought, transcribe it, search everything you've ever said.

**Phase 1 — platform speech.** `SFSpeechRecognizer` and Android `SpeechRecognizer` behind the
existing `SpeechRecognizer` / `SpeechSynthesizer` interfaces, which are `commonMain`-only stubs
today. This is what `reaktor-media` actually needs regardless of what happens to phase 2. Adds a
background processing queue and full-text search over a growing archive. Recordings are sensitive,
so encryption at rest matters here.

**Phase 2 — on-device model.** whisper.cpp through `reaktor-ffi`, FlexBuffers moving payloads across
the boundary. This is the honest test of `reaktor-ffi`, and it is also a multi-week build across
four targets that can swallow the whole quarter. Start it only after phase 1 has shipped.

*Forces:* speech/audio capture · background processing · full-text search · encryption at rest · then FFI + flexbuffer
*Size:* 2–3 weeks phase 1, 3–4 weeks phase 2

### 8. Commute companion
**Primary stress: data scale.**
**Demoted: widgets moved to idea 5; blocked on a feasibility check.**

Offline timetables, live delays, next departures.

Transit feeds are large structured datasets, so this is the first app giving `reaktor-performance`
and the flexbuffer work a production workload rather than a benchmark.

**Blocking precondition:** the entire app collapses if your city has no usable GTFS or live-delay
feed. Verify feed availability and licence *before* scheduling any work. If the feed is weak, drop
this idea — idea 6 already covers the daily-glance habit and idea 5 already covers widgets, so
nothing else in the portfolio depends on it except data-scale, which the money manager's
multi-year transaction history covers as a secondary.

*Forces:* data scale · location · realtime updates · background budgets
*Absorbs:* geofenced reminders
*Size:* 2–3 weeks, if the feed exists

### 9. Scheduling link — a personal Calendly
**Primary stress: server contracts + public web + temporal correctness.**
**Kept, sequenced last.**

People open a public page and book time with you; you manage it from the phone.

Guests must use it *without installing anything*, which forces the Kotlin/JS web target to be real
rather than theoretical, and forces deep links, Cloudflare Email and a substantial worker sharing
request/response types with the client — the "define once, mount on both sides" claim, finally
tested. Timezone and recurrence math is its own category of hard and nothing else here goes near it.

It has the best forcing function and the weakest habit loop: unless strangers are booking time with
you, there is no reason to open it. That is why it is last rather than cut — the framework value is
real even if the personal value isn't.

*Forces:* shared client/server contracts · public web target · temporal correctness · email · deep links
*Size:* 3–4 weeks

### 10. Read-aloud / voice reader
**Primary stress: speech *synthesis* + full-duplex voice control.**
**New — September 2026. Complements Recorder: it owns the output half of `reaktor-media/speech`.**
**Design doc: [lector.md](lector.md)** — *Lector* is a working name, rename later.

Point it at a PDF and it reads aloud, hands-free. Talk back — "highlight every date," "note: check
this figure," "next page" — and it highlights, annotates and scrolls itself, following the spoken
word.

The costume test first, because this doc has failed it before: the read-later reader is already
Cairn II, media playback is already Podcast, ingestion is already Cairn's `ShareReceiver`, and speech
*recognition* is already Recorder. What nothing owns is exactly three things, and they are the whole
reason this is an entry and not a feature —

- **The synthesizer half of speech.** `reaktor-media/.../speech/SpeechSynthesizer` is a `commonMain`
  stub with no Android or Darwin body. Recorder forces the *recognizer* impl and never needs to
  speak; this is the first thing that does. Together the two ideas make `reaktor-media/speech` real
  in both directions.
- **Full-duplex voice control.** Doing capture and output at once is the hard part: speaking while
  listening for a command, ducking or pausing the synthesizer the instant the mic opens, resuming
  cleanly after. That barge-in loop — two half-duplex OS engines contending for audio focus — is a
  primitive nothing else in the portfolio builds, and it is what makes this an assistant rather than
  a play button.
- **Reading-follow.** Both OS engines emit a word range as they speak — iOS
  `willSpeakRangeOfSpeechString`, Android `onRangeStart`. That callback drives highlight and
  auto-scroll for free: no cloud TTS, no per-character bill. The work is mapping the range back onto
  the rendered PDF text layer and keeping the viewport ahead of the voice.

Everything else is deliberate reuse, named so it isn't re-costed later: ingestion is Cairn's inbound
`ShareReceiver` (share a selection, URL or PDF from any app — the cross-platform, store-safe "any
app" story), the long-lived speaking session is Podcast's out-of-route player scope, and the mic
grant is the existing `PermissionAdapter` (`speech` is already a constant). New work lands only where
the three forces above do.

Commands split in two tiers the way sync does: a literal grammar (`highlight <phrase>`, `next`,
`pause`, `note …`) on-device, and a semantic tier ("highlight the argument, not the examples")
through an LLM in a `reaktor-cloudflare` worker. Ship the grammar first; the worker is phase two.

*Ambient stretch, Android only:* an `AccessibilityService` + overlay bubble that reads whatever app
is in front instead of pulling text in by share. iOS forbids it, and it can neither highlight-in-place
nor reliably scroll a foreign app — a late Android-only power feature with real Play-policy exposure,
never the MVP, never promised on iOS. Gate on a policy check.

*Forces:* speech synthesis + platform impl · full-duplex voice control (barge-in) · reading-follow (word-range highlight + auto-scroll) · on-device intent grammar, then LLM intent
*Reuses:* Cairn `ShareReceiver` · Podcast player scope · existing `PermissionAdapter`
*Size:* ~2 weeks once the synthesizer impl exists (read + highlight + auto-scroll, then the command loop); +1 week for the semantic tier; ambient overlay separate and later

---

## 5. Coverage — each hard problem appears once

| Hard problem | Owned by |
|---|---|
| Multi-target parity (phone + desktop + web) | Cairn I |
| Realtime + presence | Cairn I |
| Encryption in transit / at rest | Cairn I (Recorder secondary) |
| MLS membership churn | Cairn I (device set as group); BestBuds for people |
| Identity across moving sources (reconciliation, not sync) | Cairn II |
| Graph scoping (long-lived node) | Podcast |
| Media playback | Podcast |
| Sync: simple (scalar, last-write-wins) | Podcast |
| Sync: record-level, one user many devices | Money manager |
| Sync: collaborative, many users concurrent | Kitchen |
| Forms & validation | Money manager |
| i18n — currency and numbers | Money manager |
| i18n — units and measures | Kitchen |
| Accessibility (reference implementation) | Money manager |
| Widgets / live activities | Steps & health |
| Health + sensor adapters | Steps & health |
| WebView | Cairn II |
| Inbound share / OS ingestion | Cairn I and II |
| Full-text search | Cairn II, Recorder |
| Media capture (audio, camera) | Recorder, Kitchen phase 2 |
| Speech synthesis (TTS out) + platform impl | Voice reader |
| Full-duplex voice control (barge-in, duck) | Voice reader |
| Reading-follow (word-range highlight + auto-scroll) | Voice reader |
| On-device compute (FFI / C++) | Recorder phase 2 |
| Data scale & query performance | Money manager (Commute, if it happens) |
| Multi-source ingestion (open-ended corpus) | Cairn II |
| Temporal correctness (timezones, recurrence) | Scheduling |
| Public web target (Kotlin/JS) | Scheduling |
| Background: downloads / refresh / processing | Podcast / Steps / Cairn II / Recorder |
| Motion & tactile feedback | Kitchen (eyes-free), then everywhere |
| Permissions (UI layer above the existing adapter) | Every app — mic, camera, location, notifications, health |

**New framework modules these force into existence:** motion + tactile, i18n, accessibility,
widgets, forms, CLI scaffolding — plus a real sync layer completing the change feed that
`ObjectDatabase` already emits, a permissions **UI** layer above the adapter that already exists, and
the synthesizer + full-duplex voice-control half of `reaktor-media/speech` (idea 10) beside the
recognizer half idea 7 builds.

---

## 6. The one cross-cutting workstream: sync

Three apps need sync at escalating difficulty, and building it three times would be the single
biggest waste available. Build it once, in tiers, on top of the change feed already in
`ObjectDatabase`:

0. **Substrate — in flight.** `DatabaseEvent` with `Origin { Local, External, Sync, Migration }`
   already emits from `ObjectDatabase`. Finish it: transport, an operation log, and reconnect.
1. **Podcast** — one scalar per record, last-write-wins is correct. Establishes transport, change
   feed consumption, and reconnect behaviour.
2. **Money manager** — record-level merge, one user across devices. Adds per-field conflict
   resolution and a durable operation log.
3. **Kitchen** — concurrent multi-user edits on the same list, plus binary attachments. Adds real
   conflict semantics and partial sync.

Doing them in this order means each step is a small increment on working code. Starting at tier 3
cold is how sync layers get rewritten twice.

---

## 7. Sequencing

**First — Cairn arc I.** Changed from the original draft, which opened with Podcast. Cairn arc I is the
smallest shippable thing on the list and it answers the question with the widest blast radius: does one graph
really run on phone, desktop and web? That answer changes the shape of every app after it, so it
should not be discovered in month three. Plan: [cairn.md](cairn.md).

**Second — Cairn arc II**, on arc I's backend. It is the only work here with a need that exists
today rather than in principle, and it is cheap because arc I already built the substrate. The cost is honest: it delays the spine by about three and a half weeks. Worth it,
because a portfolio nobody uses daily is how the earlier ten-idea list died.

**Step 0.5 — CLI scaffolding.** After Cairn arc I proves what a three-shell layout needs, before app
number three. See §9.

**Then the spine** — build in order, each reusing the last:
Podcast → Money manager → Kitchen.

**Parallel track** — independent, pick up when the spine blocks:
Steps & health (smallest, unblocks widgets), Recorder phase 1.

**Voice reader (idea 10)** — after Recorder phase 1 (which builds the shared `SpeechSynthesizer`
platform interface) and after Cairn ships `ShareReceiver`. Reuse-heavy, so it only turns cheap once
both exist; not before app three.

**Last / conditional:** Scheduling (last), Commute (only if the transit feed checks out),
Recorder phase 2 and Kitchen phase 2 (only after their phase 1 ships).

---

## 8. Ship one all the way

BestBuds, Manna and GymBuddy are already in flight, and the git history carries messages like
*"we need to figure out how to fit everything into our heads better"* and *"need to inventory and
reduce code, make it more elegant."* Nine more half-finished apps would make that worse.

Taking one all the way to **shipped, in a store, with strangers using it** stresses Reaktor in ways
no prototype can: process death, permission denials, migration across versions, cold start, crash
triage with real Crashlytics data, store review. There are telemetry, performance and crash modules
in the repo with no real user data flowing through any of them.

**The named ship target is Cairn arc I.** It is the smallest, it has no sensitive data class to defend
in review, no bank-data compliance surface, and no external feed to depend on. Steps & health is the
fallback if Cairn's three-target scope slips. Money manager is explicitly *not* the first ship —
financial data raises the review and privacy bar for no framework gain.

Three finished beats nine started.

---

## 9. Step 0.5 — `reaktor-cli new`

`reaktor-cli` has `Commands`, `ProjectCommands`, `Lifecycle`, `Topology`, `Ops`, `DeployPicker`,
`Install` and `Docs` — and no scaffolding command. Nine apps hand-rolling KMP module layout,
target declarations, podspec, webpack config, manifest and entitlements is the largest avoidable
cost in this plan, and every copy-pasted layout is a divergence to fix later.

Build `reaktor-cli new <app>` after Cairn arc I proves what the three-shell layout actually needs, and
before app number three. Cairn's two arcs are the template's own first customers, which is the only
way the template ends up correct — arc II especially, since it extends an existing app's graph rather
than starting from nothing.

---

## 10. Infrastructure cost

Not free, and worth stating before the first Durable Object goes up:

| App | Needs | Recurring |
|---|---|---|
| Cairn I | Workers, Durable Objects (rooms/presence), R2 (blobs) | Small, scales with transfer volume |
| Kitchen | Workers, Durable Objects (list state), R2 (attachments) | Small |
| Scheduling | Workers, D1 or DO (bookings), Cloudflare Email, public hostname | Small + domain |
| Cairn II | Rides on arc I's Workers/DO/R2; adds D1 + retained blob storage | Grows with corpus |
| Podcast, Money, Steps, Recorder | Sync endpoint + object storage only | Minimal |

Everything else is on-device. The sync endpoint is shared across all of them — one more reason to
build it once (§6). Budget for a domain, a Workers paid plan once Durable Objects are in play, and
R2 egress on Cairn.

---

## 11. Tracking

Status of each idea. Update as work starts; per-app plans go in `plans/<app>.md` when picked up.

| # | App | Status | Blocked on | Plan doc |
|---|---|---|---|---|
| 1 | Cairn arc I (transfer) | Not started | — | [cairn.md](cairn.md) |
| 2 | Cairn arc II (retention) | Not started | arc I ph.0–6 | [cairn.md](cairn.md) |
| 0.5 | `reaktor-cli new` scaffolding | Not started | Cairn arc I shipping first | — |
| 3 | Podcast | Not started | Cairn arc I | — |
| 4 | Money manager | Not started | Podcast (sync tier 1) | — |
| 5 | Kitchen ph.1 | Not started | Money (sync tier 2) + second-user confirmation | — |
| 6 | Steps & health | Not started | — | — |
| 7 | Recorder ph.1 | Not started | — | — |
| 8 | Commute | **Blocked** | Transit feed availability check | — |
| 9 | Scheduling | Not started | — | — |
| 10 | Voice reader | Not started | `SpeechSynthesizer` impl (shared w/ Recorder) · Cairn `ShareReceiver` | [lector.md](lector.md) |
| — | Cairn ph.12 (extraction) | Deferred | arc II in daily use | [cairn.md](cairn.md) |
| — | Kitchen ph.2 | Deferred | Kitchen ph.1 shipping | — |
| — | Recorder ph.2 (FFI) | Deferred | Recorder ph.1 shipping | — |
| — | Voice reader ambient overlay (Android) | Deferred | Play-policy check; iOS excluded | [lector.md](lector.md) |

**Open decisions awaiting an answer:** Cairn §15 (seven; 1–6 before its phase 3, 7 before phase 7) ·
Commute transit-feed check · Kitchen second-user confirmation · Voice reader Android ambient-overlay
Play-policy check (iOS ships share-pull only).

---

## Appendix — evidence

Claims verified against the source tree, August 2026.

| Claim | Source |
|---|---|
| Manna owns MCP | `reaktor-auth/src/commonMain/.../api/AuthService.kt` (`manna-mcp` default audience) |
| Manna = knowledge maps / flows on Memgraph | `reaktor-flow/src/commonMain/.../document/GraphDocument.kt` |
| Sync is a whole-file snapshot | `reaktor-db/src/commonMain/.../adapters/SyncAdapter.kt` |
| Change feed with `Origin` exists | `reaktor-db/src/commonMain/.../ObjectDatabase.kt` (`DatabaseEvent`, `Origin`) |
| A `SyncWorker` shape already exists | `reaktor-work/src/commonMain/.../workers/SyncWorker.kt` |
| Permission adapter **does** exist, cross-platform | `reaktor-core/src/{commonMain,androidMain,iosMain}/.../adapters/PermissionAdapter.kt` |
| `ripple` / `haptic` absent; animation confined to the canvas | repo-wide search, `*.kt`, excluding `build/` |
| `i18n` absent | repo-wide search, `*.kt`, excluding `build/` |
| `reaktor-tactile` has no source files | `reaktor-tactile/` — build files and README only |
| Speech has no platform implementation | `reaktor-media/src/commonMain/.../speech/` only; no `androidMain`/`iosMain` |
| Speech splits recognizer (idea 7) / synthesizer (idea 10) | `reaktor-media/.../speech/{SpeechRecognizer,SpeechSynthesizer}.kt` |
| `reaktor-security` is MLS | `reaktor-security/cpp/external/mlspp/`, key packages, group conversations |
| `reaktor-web` has real WebViews, no consumer | `reaktor-web/src/{commonMain,androidMain,iosMain}/.../WebView.kt` |
| `reaktor-health` / `reaktor-sensors` have no consumer | `reaktor-health/src/`, `reaktor-sensors/src/` — commits `c37a360d`, `adb533b0` |
| `reaktor-cli` has no scaffolding command | `reaktor-cli/src/main/.../{Commands,ProjectCommands}.kt` |
| `ShareAdapter` is outbound only | `reaktor-io/src/commonMain/.../adapters/ShareAdapter.kt` — `shareFile(payload)`; no inbound path |
| Worker/DO/R2/D1/PartyServer primitives exist | `reaktor-cloudflare/src/jsMain/.../{Router,DurableObjects,R2,D1,PartyServer}.kt` |
| Services mount on both Spring and Workers | `reaktor-service/src/{commonMain,jvmMain,jsMain}/.../` |
| ~1,289 planning/docs markdown files across five repos | `find` across bestbuds, graphify, greplica, gymbuddy, reaktor |
| 10 background worker shapes exist, unproven | `reaktor-work/src/commonMain/.../workers/` |
| Telemetry hooks lifecycle/nav/ports non-invasively | `reaktor-telemetry/.../GraphTelemetry.kt` |
