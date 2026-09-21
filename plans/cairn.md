# Cairn — design & implementation

*Apps #1–2 in [app-portfolio.md](app-portfolio.md) · design doc · August 2026 · status: not started*

Drop anything in, from any device, at any time. It crosses to your other devices immediately, it
stays, and it tracks what you still owe on it.

**On the name.** A cairn is built by travellers dropping one stone at a time. It persists, and it
marks where the route goes next. That is both halves of this app — ephemeral transfer in phase one,
durable retention and obligation tracking in phase two — under one word.

**This is one app, built in two arcs.** An earlier draft split it into "Handoff" and "Inbox" and
gave each its own document, which was wrong: the second arc adds four nodes to the first arc's
graph, reuses its Worker, its Durable Object, its R2 bucket, its shells, its auth and its crypto,
and fills two fields the first arc already writes as null. The split was a build order wearing a
product boundary's clothes. The portfolio keeps two rows because the two arcs own two different
framework problems; the app is one.

- **Arc I — transfer.** Send a link, a file or a snippet across your devices. Ships to a store.
- **Arc II — retention.** Everything stays, is searchable and readable, and carries state for what
  it obliges you to do.

---

## 1. Why this is first

Cairn answers the question with the widest blast radius: **does one Reaktor graph really run on
phone, desktop and web?** Every app after it inherits that answer, so it should not be discovered in
month three.

Arc II then makes it the only app in the portfolio with a need that exists today rather than in
principle — see §3 — which is what keeps it in daily use, which is what makes it a real test rather
than a prototype.

| Question | How Cairn answers it |
|---|---|
| Does one graph run on three shells? | Same `Graph` + nodes; only shell chrome differs |
| Is the service model really define-once-mount-twice? | One `Service` subclass mounted on Workers, consumed by all clients |
| Does `reaktor-security` integrate? | First consumer ever — device set as an MLS group |
| Do the realtime primitives work outside a demo? | `PartyServer` room per user, presence + fanout |
| Does `reaktor-cloudflare` R2 work under load? | Binary transfer path |
| Can we ingest from the OS? | Inbound share target — **new work**, see §7 |
| Can we reconcile the same thing arriving twice? | Identity across moving sources, §8 — unclaimed elsewhere |

---

## 2. Scope

**Arc I, in scope:**

- Three shells — Android, desktop (JVM), web (Kotlin/JS) — running the same graph.
- Send text, a URL, or a file from any shell to the others.
- Live device presence.
- End-to-end encryption, keys never leaving the device set.
- Ephemeral by default: drops expire.

**Arc II, in scope:**

- Retention: drops stop expiring and become documents.
- Feeders beyond the share sheet — folder watch, URL fetch, email-in.
- Full-text search across everything ever dropped.
- Reading: markdown and PDF, offline, on every shell.
- Revision linking: the same document arriving again recognised as a new version.
- *Deferred sub-phase:* obligation extraction, §11.

**Out of scope, both arcs:**

- iOS shell in v1. Deliberate — see §14.
- Sharing with other people. This is your own device mesh; BestBuds owns person-to-person.
- Editing documents. Cairn reads and tracks; documents are written elsewhere.
- Knowledge maps, concept graphs, agent flows. Those belong to Manna, §11.

**Success condition for arc I:** a file sent from the Android app appears on desktop and web within
two seconds, from a single graph definition compiled to all three targets, shipped to a store.

---

## 3. The problem arc II solves, measured

Planning and review documents accumulate faster than they get executed. Counted across the working
set in August 2026:

| Repo | `.md` files |
|---|---|
| bestbuds | 494 |
| graphify | 361 |
| reaktor | 226 |
| gymbuddy | 197 |
| greplica | 11 |

~1,289 total, of which roughly twenty generate real obligations — `bestbuds/agent/*-review-*.md`,
`bestbuds/models/*_PLAN.md`, `graphify/worked/*/review.md`, plus whatever lands in Downloads.

**Why a todo note fails.** Status is written *in prose, inside each document*.
`CHAT_ENCRYPTION_PLAN.md` is 85 KB with headings like *"§0 Verified starting state (2026-08-14)"*,
*"§3.1 Which chat types — scoped 2026-08-15"*, *"§5.1 Why MLS — settled"*. Only 2 of ~20 planning
documents use checkboxes at all. Current state gets re-derived by re-reading an 85 KB file, and any
note taken from it is a hand-copy that goes stale the moment the document is revised.

**Why a reading list also fails.** Its unit is the file; its states are unread and read. The pain is
not unread documents — they get read. It is not knowing what is still owed from documents already
read. The file above holds three obligations at three different states; file granularity cannot
express that.

**The shape that works** is visible in this repo. `app-portfolio.md` opened with *"supersedes the
earlier ten-idea list"*, then was itself revised — four claims corrected, two apps added, the
sequence reordered. A reading list shows two entries, both read. What is needed is one thread
showing what died, what was corrected, and what is still open. Supersession, not a checkmark.

**The decision taken: build the reading list on the tracker's data model.** Content-addressed
identity and a supersession edge cost almost nothing now and are expensive to retrofit once there is
data. Extraction is deferred until the corpus is real and daily use has shown what it needs.

---

## 4. Product surface

Three shells, one graph, deliberately thin UI.

**Android.** A share target — the primary entry point. Share from any app into Cairn and it lands on
your other devices. A list of recent drops, tap to open, notification on arrival. Arc II adds
reading and search.

**Desktop (JVM).** A window with a drop zone and the same list. Paste with `Cmd+V`, drag a file in,
click a received drop to reveal it on disk. Where received things get used, and in arc II where the
folder-watch feeder runs.

**Web (Kotlin/JS).** Same list, same send. Exists to prove the target compiles and runs, and because
a browser is sometimes all that is available.

No settings screen in arc I beyond device naming and sign-out.

---

## 5. Architecture

### 5.1 Client graph

One `Graph` per shell, identical composition. Arc II nodes marked.

```
CairnGraph
├── SessionNode        (ControllerNode)  — auth state, device identity
├── RoomNode           (ControllerNode)  — websocket lifecycle, presence, reconnect
├── DropStoreNode      (ControllerNode)  — local ObjectDatabase store, change feed
├── CryptoNode         (ControllerNode)  — MLS group ops, seal/open
├── TransferNode       (ControllerNode)  — R2 up/download, progress
├── ShellRoute         (RouteNode)       — the list screen
├── LibraryNode        (ControllerNode)  — arc II: documents, revisions, state
├── IndexNode          (ControllerNode)  — arc II: incremental full-text index
├── FeederNode         (ContainerNode)   — arc II: one child per active source
└── ExtractionNode     (ControllerNode)  — arc II deferred: queue consumer
```

Ports rather than direct calls, per the runtime's own rule:

| Provider | Consumer | Payload |
|---|---|---|
| `SessionNode.identity` | `RoomNode`, `CryptoNode` | `DeviceIdentity` |
| `RoomNode.inbound` | `DropStoreNode` | `RoomEvent` |
| `CryptoNode.opened` | `DropStoreNode` | `Drop` |
| `DropStoreNode.outbound` | `RoomNode` | `SealedEnvelope` |
| `DropStoreNode.retained` | `LibraryNode` | `Drop` |
| `LibraryNode.indexable` | `IndexNode` | `DocumentText` |
| `FeederNode.received` | `DropStoreNode` | `ReceivedShare` |
| `TransferNode.progress` | `ShellRoute` | `TransferProgress` |

`autoWire()` resolves these by type within the graph; nothing needs a manual edge except where two
ports share a type, which is why `RoomEvent` and `Drop` stay distinct types rather than one union.

`RoomNode` and `DropStoreNode` must outlive route changes. On desktop and web that is trivial; on
Android it is the process-death case — the same long-lived-scope question Podcast will hit harder.
Cairn gets the easy version first on purpose.

### 5.2 Server

A single Worker, built from `reaktor-cloudflare`:

- **Router** (`Router.kt`) — HTTP endpoints, JWT-verified via `WorkerJwtVerifier.kt`.
- **`CairnRoom`, a PartyServer Durable Object** (`PartyServer.kt`) — one instance per user, named by
  the `sub` claim. Holds connections, presence, and a short tail of recent drop metadata so a
  reconnecting device catches up without a full fetch.
- **R2** (`R2.kt`, `R2FileAdapter.kt`) — ciphertext blobs, keyed by content hash.
- **D1** (`D1.kt`) — drop and document metadata. Arc I alone could use the DO's own SQLite; D1 is
  chosen because arc II needs cross-document queries and a migration later would be worse.

The Worker never sees plaintext and never holds a key.

### 5.3 Protocol

Small payloads travel inline; large ones go through R2.

```
send (inline, ≤ 64 KiB ciphertext)
  client → DO   : Publish(envelope)
  DO → clients  : Deliver(envelope)          // fanout, excluding sender

send (blob)
  client → Worker : POST /drops/upload-url   → { key, url }
  client → R2     : PUT url (ciphertext)
  client → DO     : Publish(envelope)        // references key, carries content key
  DO → clients    : Deliver(envelope)
  client → R2     : GET (on demand, not eagerly)
```

The 64 KiB threshold is a starting guess, not a measurement — see §15.

Reconnect: on open a client sends `Sync(since)`; the DO replies with metadata newer than that
cursor. Blobs are fetched lazily. Same change-feed-plus-cursor shape sync tier 1 needs in Podcast,
which is intentional.

### 5.4 Crypto

Your devices form an **MLS group**, using `reaktor-security`.

Better fit than it first appears. Two devices exchanging a file is a thin use of MLS, but a *device
set* is not two devices — it is a group whose membership changes every time you add a laptop,
reinstall the app, or lose a phone. Add and remove are exactly what MLS exists for, and forward
secrecy across a device you no longer control is a real requirement, not a theoretical one.

- Each install generates a device keypair and publishes a key package.
- The first device creates the group; later devices are added by an existing device confirming them.
- Drops are sealed to the current group epoch. Removing a device advances the epoch, so it cannot
  read anything sent afterwards.
- Blob content keys travel inside the sealed envelope, never in R2 metadata or a D1 row.

**Device removal needs a trusted path.** Confirming a new device from an existing one requires a
short numeric comparison shown on both — otherwise anyone with a stolen session token joins the
group. This is the one genuinely security-sensitive piece and should be reviewed before release,
not after.

---

## 6. Data model

Content-addressed from the start. Arc I writes the fields arc II depends on, even where it leaves
them null — that is the whole reason the fork was decided this way.

```kotlin
@Serializable
data class Drop(
    val id: DropId,                       // content hash of plaintext bytes — NOT a random uuid
    val kind: DropKind,                   // Text, Link, File
    val mime: String,
    val sizeBytes: Long,
    val label: String,                    // filename, page title, or first line
    val origin: DeviceId,                 // which device produced it
    val createdAt: Long,
    val retention: Retention,             // Ephemeral(expiresAt) | Retained
    val supersedes: DropId? = null,       // arc I always null; arc II fills it
    val blobKey: String? = null,          // R2 key when not inline
)
```

Three decisions worth stating plainly, because reversing any of them is expensive:

**`id` is a content hash.** The same file shared twice is the same drop arriving twice. Arc I uses
this to deduplicate; arc II uses it to recognise a document it has seen before through a different
route, which is the entire reason arc II works.

**`retention` exists in arc I** even though everything is `Ephemeral`. Arc II flips drops to
`Retained` rather than introducing a parallel table.

**`supersedes` exists in arc I** and is always null. Adding a nullable field later is easy; adding it
after two arcs have written a million rows and built queries assuming it absent is not.

Arc II adds documents on top, without replacing drops:

```kotlin
@Serializable
data class Document(
    val documentId: DocumentId,
    val contentHash: DropId,          // this version's bytes
    val supersedes: DropId?,          // previous version, if any
    val title: String,
    val sources: List<SourceRef>,     // every route it arrived by
    val firstSeenAt: Long,
    val revisedAt: Long,
    val state: DocState,              // Active | Archived | Superseded
)
```

**Local persistence** is `ObjectDatabase` from `reaktor-db`:

```kotlin
val drops = database.store("drops") { /* config */ }
```

Reads and writes carry an explicit `Origin` — `Origin.Local` for what this device produced,
`Origin.Sync` for what arrived from the room. The `DatabaseEvent` change feed is what the UI
observes; nothing polls.

**Do not use `SyncAdapter`.** It uploads a whole-database snapshot and restores over the top, so two
devices dropping things offline silently clobber each other. The change feed already on
`ObjectDatabase` is the substrate; Cairn consumes it directly and Podcast generalises it into the
real sync layer.

**Sync tiering.** Document state — read, archived, item done — is per-user across devices, which is
exactly the tier-2 record-level case owned by the Money manager. Cairn is a *consumer* of that
layer, not where it gets built. Until tier 2 exists, arc II uses last-write-wins on state fields:
wrong in a rare case, acceptable for a single user.

**Search** is SQLite FTS5 on-device over title plus extracted text. Server-side search is
deliberately not built — the corpus is small per user, local search is faster, works offline, and
means the server never needs plaintext for any feature in scope. That is also what keeps the E2EE
design coherent.

---

## 7. Ingestion: feeders, not a scanner

The defining constraint: **documents arrive from anywhere, at any time, and the corpus is
open-ended.** Not one machine, not one repo checkout, not a one-time import. An earlier draft
proposed a CLI that scans local repositories; that is device-bound and checkout-bound and is the
wrong foundation. It survives as one feeder among several.

A feeder is a small, independent component producing `ReceivedShare`-shaped input:

| Feeder | Shell | Arc | Notes |
|---|---|---|---|
| Share sheet | Android | I | `ShareReceiver`, below |
| Drag / paste | Desktop | I | Same adapter, different mechanism |
| Web Share Target | Web | I | Requires installed PWA; degrades to file picker |
| Folder watch | Desktop | II | **Optional.** Point it at `bestbuds/models`, `reaktor/plans`, anything. How the 1,289 local files get in — as a feeder, not as the architecture |
| URL fetch | Any | II | Paste a link, a worker fetches and stores readable content |
| Email-in | Server | II | Cloudflare Email routing to a per-user address. Later |

Adding a feeder must never require touching storage, identity or the UI. That is the test of whether
this abstraction is real.

**Consequence: extraction is a queue, not a pass.** Documents arrive whenever, so processing is
per-item background work — the first honest workload for `reaktor-work`, which has ten worker shapes
and no production consumer.

### The framework gap: inbound share

`ShareAdapter`, currently uncommitted in `reaktor-io`, is **outbound only**:

```kotlin
abstract suspend fun shareFile(payload: SharePayload): Boolean
```

It hands a file *to* the platform share sheet. Cairn needs the opposite — receiving content the user
shares *into* the app — and nothing in the repo does that. An earlier draft counted this as reuse;
it is new work.

Proposed addition, same module:

```kotlin
@Serializable
data class ReceivedShare(
    val mime: String,
    val text: String? = null,
    val fileUris: List<String> = emptyList(),
    val sourceApp: String? = null,
)

abstract class ShareReceiver<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    /** Emits each share the OS routes to this app, including the one that cold-started it. */
    abstract val incoming: Flow<ReceivedShare>
}

var Feature.ShareReceiver by CreateSlot<ShareReceiver<*>>()
```

Platform cost, honestly:

- **Android** — `intent-filter` for `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, plus reading the intent
  on cold start. Half a day.
- **Desktop** — drag-and-drop and clipboard, not a share sheet. Different mechanism, same adapter.
- **Web** — Web Share Target API; requires an installed PWA and a manifest entry. Degrades to paste
  and file picker.
- **iOS** — a Share Extension is a **separate binary** with its own bundle id, communicating through
  an App Group container. It cannot import the KMP framework casually. The single biggest reason iOS
  is out of scope for v1.

---

## 8. The hard part: identity across moving sources

This is the piece worth owning, and the piece a todo note fundamentally cannot do.

The same document arrives repeatedly, by different routes, under different names:

```
reaktor-app-ideas_1.md          dropped from Downloads, 2026-08-22
  → plans/app-portfolio.md      committed into the repo, 2026-08-22
    → revised in place          2026-08-24, four claims corrected
      → (future revision)       ...
```

One obligation thread. Four arrivals. Three filenames. Path-keyed storage sees four unrelated
things; a reading list marks four items read.

**Model:**

- `contentHash` — identity of the *bytes*. Re-arrival of identical content is recognised and
  ignored. Already how `Drop.id` works in arc I.
- `documentId` — identity of the *thing*, stable across revisions. Assigned on first arrival,
  carried forward when a new arrival is judged a revision.
- `supersedes` — an explicit edge from the new version to the one it replaces, forming a chain.

**Deciding "is this a revision of that?" is the genuinely hard call.** Candidate signals, in
increasing cost: same path in the same source; same title heading; high textual similarity; an
explicit *"supersedes"* line in the text. None is reliable alone.

**Arc II does not guess.** It records every signal, links the obvious cases (identical path from the
same feeder), and shows the rest as a suggestion confirmed with one tap. An automatic linker that is
wrong is worse than no linker, because a silently mislinked obligation is exactly the failure the
todo note already has.

This is *not* sync. Sync reconciles replicas of your own writes; this reconciles the same external
thing arriving repeatedly through different routes. Nothing else in the portfolio goes near it.

---

## 9. Shared contracts

One `Service` subclass in `commonMain`, mounted on the Worker and called by all three shells — the
define-once-mount-on-both-sides claim, under load for the first time.

```kotlin
class CairnService(baseUrl: String) : Service(baseUrl) {
    val uploadUrl     = post<UploadUrlRequest, UploadUrlResponse>("/drops/upload-url")
    val listDrops     = get<ListDropsRequest, ListDropsResponse>("/drops")
    val registerDevice = post<RegisterDeviceRequest, RegisterDeviceResponse>("/devices")
    val removeDevice  = delete<RemoveDeviceRequest, RemoveDeviceResponse>("/devices/{id}")
    val fetchUrl      = post<FetchUrlRequest, FetchUrlResponse>("/fetch")        // arc II
}
```

Request and response types are `@Serializable` and live beside the service. Auth is a
`ServiceInterceptor` attaching the JWT; the Worker verifies with `WorkerJwtVerifier`.

The websocket protocol is *not* modelled as `Service` — it is a message union over `PartyServer`,
serialized with the same kotlinx types. Forcing request/response onto a fanout channel would be the
wrong shape.

---

## 10. Modules

**Existing, consumed:** `reaktor-core` (Feature/Adapter, permissions), `reaktor-graph` (runtime),
`reaktor-db` (ObjectDatabase), `reaktor-io` (FileAdapter, ShareAdapter), `reaktor-service`,
`reaktor-cloudflare` (Router, PartyServer, R2, D1, JWT), `reaktor-security` (MLS), `reaktor-auth`,
`reaktor-work` (retry, upload, extraction queue), `reaktor-notification`, `reaktor-telemetry`,
`reaktor-web` (arc II reading — its only consumer anywhere).

**New, in the framework:** `reaktor-io` gains `ShareReceiver` (§7). Belongs in the framework, not the
app.

**New, as an app:** Cairn itself. There is no `reaktor-cli new` scaffolding yet, so it hand-rolls its
module layout — and then becomes the template's first customer, per
[app-portfolio.md §9](app-portfolio.md).

---

## 11. Deferred sub-phase: obligation extraction

Deferred deliberately. Recorded now so nothing earlier forecloses it.

An obligation is a span inside a document with independent state:

```kotlin
@Serializable
data class Obligation(
    val id: ObligationId,
    val documentId: DocumentId,
    val anchor: String,               // heading path, e.g. "§5.2 Sequencing against RSEC"
    val text: String,
    val state: ObligationState,       // Open | Done | Dropped | Superseded
    val decidedAt: Long?,
    val evidence: String?,            // why it is in this state
)
```

Extraction runs server-side per document version, producing candidates from three sources, cheapest
first:

1. **Structural** — unchecked checkboxes, and headings carrying status markers (*"scoped
   2026-08-15"*, *"settled"*, *"blocked on"*). Deterministic, no model, catches more than expected in
   this corpus.
2. **Cross-version diff** — comparing a version to the one it supersedes shows which obligations
   changed state, more reliably than reading either version alone.
3. **Model pass** — an LLM over sections structural rules did not classify.

**Obligations carry across revisions by anchor**, and where the anchor moved, the diff decides. An
obligation whose anchor vanished is marked `Superseded`, never silently deleted.

**Boundary with Manna.** Manna owns knowledge maps, concept roadmaps and agent orchestration. Cairn
extracts obligations from documents you dropped in and tracks their state. It does not build a graph
over them, run flows, or orchestrate agents. If a feature starts looking like a knowledge map, it
belongs to Manna.

---

## 12. Open decision: where the bytes live

The one decision to make deliberately rather than by default, and it gates arc II.

Planning documents from bestbuds and gymbuddy are commercially sensitive. Two options:

**A — store ciphertext blobs in R2.** Works everywhere, web shell included, cold start on a new
device is instant. Content is end-to-end encrypted, so the server holds bytes it cannot read.
Simpler, and consistent with arc I.

**B — keep blobs local, sync only metadata and extracted items.** The desktop shell serves blobs to
other devices on demand. More private, materially more work, and the web shell degrades to metadata
and search results only.

**Recommendation: A.** Under the MLS design in §5.4 the server never has a key, so B defends against
a threat A already covers — at the cost of the web shell and a device-availability dependency. B is
worth revisiting only if a document class appears that must never leave the machine even encrypted.

Either way R2 lives in a personal Cloudflare account; egress and storage are the recurring cost in
[app-portfolio.md §10](app-portfolio.md).

---

## 13. Implementation phases

One roadmap. Each phase ends at something runnable; nothing is done until it runs on every shell in
scope for that phase.

### Arc I — transfer

**Phase 0 — skeleton (3 days).** App module, three shell entry points, empty graph, CI compiling all
three targets. *Checkpoint:* one source set builds an Android APK, a desktop jar and a JS bundle.

**Phase 1 — text over the wire (4 days).** Worker with `CairnRoom` DO; `RoomNode` connects, presence
renders; send and receive plaintext text drops. No crypto, no blobs, no persistence. *Checkpoint:*
type on desktop, see it on Android.

**Phase 2 — identity and persistence (3 days).** `reaktor-auth` JWT, device registration,
`ObjectDatabase` store, change-feed-driven UI, reconnect with cursor. *Checkpoint:* kill and reopen
the app, history intact; go offline and back, missed drops arrive.

**Phase 3 — crypto (5 days).** MLS group creation, device add with numeric confirmation, sealed
envelopes, epoch advance on removal. *Checkpoint:* Worker logs show only ciphertext; a removed
device cannot open a subsequent drop.

**Phase 4 — blobs (4 days).** Upload-url endpoint, R2 PUT/GET, content keys inside envelopes, lazy
download, progress. *Checkpoint:* a 50 MB file crosses devices and resumes after a dropped
connection.

**Phase 5 — ingestion (4 days).** `ShareReceiver` on Android, drag/paste on desktop, Web Share Target
on web. *Checkpoint:* share a URL from a phone browser; it appears on the laptop.

**Phase 6 — ship (1 week).** Arrival notifications, error states, device management, Crashlytics,
Play Store listing and review. *Checkpoint:* strangers can install it.

Arc I: ~4 weeks of build plus a shipping week — which makes the portfolio's 2–3 week estimate
optimistic once store review is counted.

### Arc II — retention

**Phase 7 — retention (3 days).** `Retention.Retained`, D1 rows, local `documents` store, a list that
does not expire. *Checkpoint:* a dropped file is still there next week.

**Phase 8 — feeders (4 days).** Feeder abstraction, desktop folder watch, URL fetch worker.
*Checkpoint:* point it at `bestbuds/models` and `reaktor/plans`; both land without either being
special-cased.

**Phase 9 — reading (4 days).** Rendered markdown and PDF on all three shells, read/unread, archive,
tags. *Checkpoint:* an 85 KB plan reads comfortably on a phone.

**Phase 10 — search (3 days).** FTS5 index, incremental update on arrival, search across the corpus.
*Checkpoint:* a phrase from a document dropped weeks ago is found offline in under a second.

**Phase 11 — revision linking (4 days).** Content-hash dedup, document identity, supersession chain,
one-tap confirmation of suggested links. *Checkpoint:* the four-arrival chain in §8 renders as one
thread.

Arc II: ~3.5 weeks after arc I ships.

**Phase 12 — extraction (3 weeks, separate decision).** Structural rules, cross-version diff, model
pass, obligation state UI. Start only after arc II has been in daily use long enough to know whether
structural extraction alone suffices.

---

## 14. Why no iOS in v1

The three-target claim is *phone + desktop + web*, and Android + JVM + JS satisfies it while
exercising three genuinely different runtimes. iOS would add a fourth platform whose distinctive
problem — Share Extensions as separate binaries over App Groups — is an ingestion problem, not a
graph-portability problem. It teaches little about the question Cairn exists to answer and costs one
to two weeks.

iOS lands with arc II, where retention makes an extension worth the trouble, or immediately after
arc I ships if the store experience is smooth.

---

## 15. Open decisions

| # | Decision | Options | Default if unresolved | Needed by |
|---|---|---|---|---|
| 1 | Inline/blob threshold | 16 / 64 / 256 KiB | 64 KiB, then measure on a real connection | Phase 4 |
| 2 | Ephemeral TTL | 24h / 7d / until all devices ack | 7 days | Phase 2 |
| 3 | Hash function | SHA-256 / BLAKE3 | SHA-256 — available everywhere; revisit if it shows in profiles | Phase 2 |
| 4 | D1 or DO SQLite for metadata | either | D1, because arc II needs cross-document queries | Phase 2 |
| 5 | Web shell auth | full auth / paired-token | Full auth; paired-token is nicer UX and more code | Phase 2 |
| 6 | Device confirmation UX | numeric compare / QR | Numeric compare — no camera dependency | Phase 3 |
| 7 | Where retained bytes live | R2 ciphertext / local-only | R2, per §12 | Phase 7 |

---

## 16. Risks

**MLS integration is unproven.** `reaktor-security` has never had a consumer. Phase 3 is the phase
most likely to overrun and it sits mid-plan. Mitigation: phases 1–2 deliver a working app without
crypto, so a slip is contained rather than blocking. Do not reorder crypto earlier for tidiness.

**Web target may not be as real as claimed.** Kotlin/JS is used for Workers today, not for a Compose
UI. If the web shell needs a separate UI implementation, that is a finding worth having in week one
— which is why phase 0 compiles all three before any feature exists.

**Blob transfer on flaky mobile connections.** Resumable upload is not free. `reaktor-work` has an
unproven `MediaUploadWorker` shape; phase 4 is its first real test and may require rewriting it.

**Extraction quality decides whether phase 12 is worth anything.** Obligations extracted wrongly are
worse than none — a wrong tracker is abandoned faster than a missing one, and the todo note already
shows what abandonment looks like. Hence the gate on real usage rather than a scheduled date.

**Revision linking that guesses.** Covered in §8: suggest, never auto-link, until the signals are
measured against the real corpus.

**Scope collapse into a notes app.** Every week of use will suggest editing, linking, tag
hierarchies. Cairn reads and tracks. Documents are written elsewhere; knowledge maps belong to Manna.

**Arc II pressure on arc I.** Arc II is the part with a need today, which is exactly the pressure to
start it first — meaning building arc I's backend inside arc II and discovering the three-shell
answer under deadline. Order stands.

---

## 17. Next

Not started. Phase 0 is the first commit; this document gains a status line per phase as they land.
Decisions 1–6 in §15 want answers before phase 3; decision 7 before phase 7.

Related: [app-portfolio.md](app-portfolio.md)
