# Coffer: design & implementation

*Working name · money manager · November 2026 · plan doc for [app-portfolio.md](app-portfolio.md)
idea 4. The name is checked in §13.*

An expense tracker you don't type into. On Android it reads the alerts your bank and UPI apps
already post, turns each into an entry, files it under a category using rules it learned from your
corrections, and folds the same payment arriving three times into one. Manual entry is how you
correct it, not how you use it. One person, offline, years of records, synced across that
person's devices once the gate is passed (tier 2: record-level).

The app is the excuse. What it is for is what the framework cannot do today: read another app's
notifications safely, parse text deterministically on every target, hold money as money and print
it the way each locale expects, give forms one shared model, read years of rows fast, migrate a
schema, and merge two devices' edits to one record without either being lost. It is also the
portfolio's **accessibility and localisation reference implementation**, and its release is gated
on both (§4.9).

---

## 1. Why this stands alone

Run against what exists now (9 November 2026), not against the portfolio's August reading.

| Piece | Already owned by |
|---|---|
| Reading other apps' notifications | **Nobody.** `reaktor-notification` posts; nothing listens |
| Deterministic text parsing, same on JVM, JS and Native | **Nobody, generally.** `reaktor-io`'s `XmlReader` parses one grammar; Lector's on-device command grammar is app code |
| Money as integer minor units + ISO 4217, locale formatting, parsing typed amounts | **Nobody.** No `NumberFormat`, `Intl` or `NSNumberFormatter` anywhere in reaktor |
| A form model: fields, validation on blur, errors beside their cause, drafts that survive the process | **Nobody.** Portfolio §2: logic in ~20 files, no shared model |
| Range reads over years of rows | **Nobody.** `ObjectDatabase` has `get`, `getAll` and nothing between |
| Schema migration | **Nobody.** `Origin.Migration` exists as a tag; no runner |
| A scope that outlives every screen, buildable from a Service | **Hearth** — `ProcessGraph`, Context adapters (hearth.md §4.1). Reused |
| Sync transport, HLC, durable outbox, reconnect | **Hearth** — tier 1, `reaktor-db/sync` (`e0a9c829`). Extended here |
| The same external thing arriving by several routes | **Cairn II** owns it in principle (cairn.md §8). Arc II is not started, so no code exists to reuse |

**It passes, and the dedupe overlap with Cairn II needs saying plainly.** Three alerts for one
payment (a bank SMS, a bank-app push, a UPI-app push) is "identity across moving sources" in
miniature: one external fact, several arrivals, different wording. What is reused from Cairn is the
*policy*, not code: record every signal, link only the certain cases automatically, show the rest
as a suggestion confirmed with one tap, and never mislink silently (cairn.md §8, "Arc II does not
guess"). What differs is the signal. Cairn compares documents by content hash and textual
similarity; Coffer compares structured facts (a UPI reference number, an amount, an account's last
four digits, a time window). Neither can borrow the other's matcher. If arc II is built later,
the shared piece worth extracting is the *suggestion surface* (a pending link, a confirm, a
reversible split), not the matching.

Against Hearth: Coffer reuses the session-graph shape and extends sync from tier 1 to tier 2. It owns
nothing Hearth owns. Against Lector: the template matcher (§4.2) is the framework form of what
Lector's command grammar does in app code. Lector is the second consumer that justifies putting it
in reaktor; it is not migrated by this work.

**The weak point is the habit and the source of alerts, not the forces.** Indian banks send their
mandatory transaction alerts by SMS. Bank apps push some of them; UPI apps push all UPI payments.
A notification-only Coffer sees UPI well, cards partly, and a bank's SMS-only alerts not at all
unless it reads them through the Messages app's notifications, which is the policy question in
§4.1. Phase 1's checkpoint (two weeks as the owner's only tracker) measures the gap with real
numbers before the gate asks about it.

---

## 2. Scope

**In, v1:**

- Android: automatic capture from an allow-list of bank and UPI apps, parsing, dedupe,
  categories, learned rules, a needs-review list, manual entry and correction.
- iOS: the same ledger with manual entry. No capture (iOS can read neither SMS nor other apps'
  notifications). Data from Android arrives with Phase 2 sync.
- Categories, monthly view, budgets per category, search, CSV export.
- Years of records: list, search and monthly roll-ups measured on a 5-year synthetic dataset.
- Locales: Indian grouping (lakh/crore), Western grouping, a comma-decimal locale, an RTL locale.
  Currency is per entry; the default is the device's.
- Accessibility: TalkBack and VoiceOver labels on every control, font scale 2.0, reduce motion,
  contrast tested in code. Release blockers (§4.9).
- Phase 2, gated (§8): entries, categories, rules and budgets synced across one person's devices.

**Out, v1 (non-goals):**

- Investments, net worth, stocks, mutual funds, crypto.
- Bill pay, payments of any kind, reminders to pay.
- Bank login, screen scraping, Account Aggregator. AA is closed to us anyway: data users must be
  RBI, SEBI, IRDAI or PFRDA regulated entities.
- Ads, analytics SDKs, crash SDKs that send transaction data anywhere.
- Shared or family ledgers (that is Kitchen's tier 3).
- An LLM or any network call in parsing or categorising.
- iOS capture. A Shortcuts / App Intents path is later work, and reaktor has no App Intents support.
- Desktop and web shells. The ledger is common code, so they are later work, not a rewrite.

---

## 2.5 What users of these apps complain about

Researched 10 November 2026 for the owner's brief: free (at least at first), and easing the
experience rather than repeating the current apps' gaps. Full write-up, evidence and sources:
`coffer/docs/user-grievances.md`. 6,053 App Store reviews of 13 apps (India and US) and 161 Hacker
News comments were read, plus Indian forum threads. Reddit (blocks our tools) and Play Store
reviews (unreachable) were not.

The largest complaints, in order: paywalls and free features taken away; automatic import that
breaks silently; bugs; support that never answers; rigid budgets; categorising that is wrong or
tedious; forced accounts; updates that make it worse; no export; data lost with no backup; card
bills and transfers counted as spending; privacy (bank credentials, lending funnels, spam calls).

Coffer's design already answers bank-link breakage (no bank login), forced accounts, ads, typing,
duplicates, privacy (no INTERNET permission at all), export and search. Its own versions of the
gaps are a listener that dies silently (phone makers' battery managers), "Other" for every payment
in the first weeks, no backup, card bills, cash and reversals counted as spending, and no promise
about staying free. Phase 1.5 (§8) closes them.

## 3. Product surface

Five screens and one list that matters most.

- **Ledger** (home). This month's entries, newest first, as ruled rows: date, payee, category,
  amount in a right-aligned column. The month's total sits above the column. At most one card
  above the list, chosen by what matters now: "3 alerts need a look" or nothing (LA10).
- **Entry.** One payment: amount, payee, category, account, note, when, and *where it came from*:
  each alert that made it, by app and time, and a Split control when more than one was merged.
- **Needs a look.** Alerts Coffer couldn't read, possible duplicates, and entries with no category.
  Each resolves in one tap: pick a category, merge, keep separate, or turn into an entry.
- **Months.** Month by month: spent, received, by category, against budgets. One number per month
  is the largest thing on screen; categories sit under it.
- **Add / edit.** The one form. Amount, payee, category, date, account, note.
- **Settings.** Notification access and the allow-list (with counts: "HDFC Bank: 214 read,
  3 not understood"), categories, rules, budgets, currency, appearance, export.

---

## 4. Architecture

```
CofferSession   (ProcessGraph, no routes; built in Application.onCreate, so the listener service
│                finds it when the system starts the process for a notification)
├── Ledger         entries, captures, categories, rules, budgets, month totals   exposePort("ledger")
├── Capture        Feature.NotificationListener → allow-list → parse → dedupe → Ledger
├── Parser         per-sender rules over reaktor-io templates (app code, fixtures)
├── Categoriser    learned rules; applied on capture; learns from corrections
└── SyncNode       Phase 2 only
     │
     └── CofferUi  (ProcessGraph, child of the session — the routes)
         ├── LedgerScreen / EntryScreen / ReviewScreen / MonthsScreen / EditScreen / SettingsScreen
         └── consumes<Ledger>("ledger")
```

The process can start with no Activity: the system binds the listener service when a notification
arrives and Coffer isn't running. That is Hearth's §4.1 problem, already solved: adapters take a
`Context`, `ProcessGraph` builds the session from whichever entry point asks first.

### 4.1 Capture: the notification listener, a new module

**Placement, by the `reaktor-module` ladder.**

1. *Existing module?* `reaktor-notification` owns notifications, but only posting them. A listener
   is its other half (the skill's "look for the other half"), so it was the first candidate.
2. *Rejected for cost.* A listener is a `<service>` bound with `BIND_NOTIFICATION_LISTENER_SERVICE`.
   Merged into `reaktor-notification`, it would put GymBuddy, Hearth and Lector into Settings →
   Notification access as apps that can read every notification, and Play Protect treats a
   sideloaded app declaring a listener as high-risk "because these permissions are frequently
   abused for financial fraud" (Play Protect developer guidance). An app must not inherit that
   from a library it uses to post a reminder. It is also the wrong direction for the dependency:
   `reaktor-notification` pulls in `reaktor-security` (MLS, C++) and, on the web,
   `reaktor-cloudflare`. A listener needs neither.
3. **So: a new module, `reaktor-notification-listener`**, a sibling like `reaktor-notification-fcm`,
   depending only on `reaktor-core`. Shape: **interface plus slot**, because absence is normal (iOS,
   desktop and web have no such thing).

```kotlin
data class PostedNotification(
    val app: String,               // package name
    val key: String,               // the system's key, stable across updates of one notification
    val postedAtMs: Long,
    val title: String?,
    val text: String?,             // EXTRA_BIG_TEXT when present, else EXTRA_TEXT
    val subText: String?,
)

interface NotificationListener {
    val access: StateFlow<ListenerAccess>          // Granted, NotGranted, Disconnected
    val posted: Flow<PostedNotification>           // only apps passing the filter, ever
    fun setFilter(allow: Set<String>)              // package names; applied before anything is copied
    fun openAccessSettings()
    suspend fun active(): List<PostedNotification> // what is in the shade now, filtered; for catch-up on connect
}
var Feature.NotificationListener by CreateSlot<NotificationListener>()
```

**Privacy is enforced in the module, not trusted to the app.** `onNotificationPosted` reads
`sbn.packageName` first and returns at once if it is not in the filter: no extras read, nothing
copied, logged or stored. Only an allowed notification becomes a `PostedNotification`. A unit test
on the Android side asserts that a filtered-out notification's extras are never touched.

**The debug override.** `adb shell cmd notification post` posts as `com.android.shell`. Debug builds
of Coffer add `com.android.shell` to the filter and read the notification's title as the sender
("HDFCBK"), so a fixture can be replayed onto the emulator exactly as the Messages app would show
it. Release builds cannot add it: the override is a `BuildConfig` flag in the app, not in the module.

**What the system can take away.** Notification access is a special app access the user grants in
Settings, not a runtime permission. The listener can be unbound by the system, and alerts posted
while unbound are missed, so on every connect Coffer reads `active()` and captures anything still in
the shade (dedupe makes this idempotent). `access` drives a persistent card on the Ledger when
access is lost: "Coffer stopped reading alerts. Turn notification access back on."

**CHECK 1, verified 9 Nov 2026: Android 15 redaction.** Yes. From Android 15's behaviour changes
for all apps: Android "will stop untrusted apps that implement a `NotificationListenerService`
from reading unredacted content from notifications where an OTP has been detected." Companion-device
associations are exempt; the gating permission (`RECEIVE_SENSITIVE_NOTIFICATIONS`) is
signature|role, not grantable to Coffer. The redaction keys on *an OTP being detected*, not on the
sender being a bank. So:
- OTP alerts are lost to Coffer. That is fine; Coffer must not store them anyway.
- A transaction alert is affected only if the detector mistakes it for an OTP. UPI alerts carry
  12-digit reference numbers, and whether the detector (on Pixels, Android System Intelligence)
  ever flags those is **unknown and must be measured on a real Android 15+ phone with real
  alerts**. The emulator likely has no detector, so a clean emulator run proves nothing here.
- Mitigation: a redacted alert is not silently lost. It fails to parse and lands in Needs a look
  as "Android hid this alert's text", and the per-app counts show it.

**CHECK 2, partly answered 9 Nov 2026: is reading SMS through the Messages app's notifications
circumvention?** No Google text names notification listeners. What the texts do say:
- Play's SMS and Call Log policy lists "SMS-based money management" ("apps that track and manage
  budget") as an exception for `READ_SMS` / `RECEIVE_SMS`, subject to the Permissions Declaration
  Form, review, the core-feature test, prominent disclosure, and only while no alternative exists.
  Budgeting apps "may not exfiltrate or share non-financial or personal SMS history".
- The same policy forbids deriving data "attributed to" SMS permissions by other means. A search
  result quotes that sentence from Google's help pages; the two policy pages fetched today did not
  show it, so its exact current wording is unconfirmed.
- Play Protect allows listeners that relay, aggregate or re-display notifications, and disallows
  "apps that access notification content without explicit user consent".
- One self-reported case: an Indian expense tracker's SMS auto-import was rejected, with manual
  entry named as the alternative.

**Reading:** pulling bank SMS text out of the Messages app's notifications is the same data the SMS
policy protects, reached around the declaration. A reviewer could reasonably call that
circumvention. Reading a bank app's or a UPI app's *own* notifications is not SMS data and is
defensible with explicit consent. So v1 on Play is **bank and UPI apps only**; the Messages app is
off the default list and its inclusion is a gate question (§8, G1) alongside the declared-SMS route.
The owner's sideloaded build may include it for the Phase 1 checkpoint, so the checkpoint measures
how much each route actually sees.

**The allow-list, v1** (package names to verify against each Play listing before Phase 1 ships):
Google Pay `com.google.android.apps.nbu.paisa.user`, PhonePe `com.phonepe.app`, Paytm
`net.one97.paytm`, BHIM `in.org.npci.upiapp`, HDFC Bank `com.snapwork.hdfc`, YONO SBI
`com.sbi.lotusintouch`, ICICI iMobile `com.csam.icici.bank.imobile`, Axis `com.axis.mobile`, Kotak
`com.msf.kbank.mobile`. Each can be switched off. Adding another installed app is Phase 1, behind
the same disclosure.

**Prominent disclosure** (Play User Data policy) comes before the system settings screen, in the
app, not only in the privacy policy. It names what is read (alerts from the listed apps), what
happens to it (parsed on this phone, kept on this phone), what is never done (no other app's
notifications read, nothing sent anywhere), and asks for an explicit yes. Declining leaves a working
manual ledger (IN9).

### 4.2 Parsing: deterministic templates, rules per sender

**The framework part: a template matcher in `reaktor-io`**, beside `XmlReader` (parsers live in
`reaktor-io`; no permission or SDK, so a package, not a module). A template is literal text with
typed holes:

```
Sent {amount} From HDFC Bank A/C {account} To {payee} On {date} Ref {ref}
```

- Literals match case-insensitively, with any run of whitespace or line breaks equal to one space,
  and optional punctuation marked explicitly (`A/C{.}`).
- A hole is a named, typed reader: the app registers `amount`, `account`, `date`, `ref`, `payee`;
  the framework ships `text` (lazy, up to the next literal), `digits`, `word`, `skip`.
- Matching is a hand-written scanner over `Char`s. **No regex**, so it behaves identically on the
  JVM, Kotlin/JS and Native, and none of the `reaktor-module` skill's Kotlin/JS regex traps apply.
- A match returns captures with their spans; a miss returns *where* it stopped (the literal it
  expected), which is what a test failure and the Needs a look screen both want to show.

Lector's command grammar (`highlight <phrase>`, `next`, `note …`) is the same shape, which is why
this goes in the framework.

**The app part: rules per sender, each with fixtures.** A rule has an id, a version, the senders it
applies to (an app package, or an SMS sender ID such as `HDFCBK` read from the Messages app's
title), and templates for each outcome:

```kotlin
rule("hdfc.upi.sent", version = 1) {
    from(sms("HDFCBK"), app("com.snapwork.hdfc"))
    spend("Sent {amount} From HDFC Bank A/C {account} To {payee} On {date} Ref {ref}")
    ignore("{skip} is your OTP {skip}")            // counted, never stored
}
```

Every alert ends in exactly one of three outcomes, and all three are counted per app:

| Outcome | Stored | Shown |
|---|---|---|
| **Read**: a template matched | the capture (text kept on this device) and an entry | the entry |
| **Ignored**: an `ignore` template matched (OTPs, offers, balance reminders) | nothing; the text is dropped in memory | the count only: "41 OTPs and offers skipped" |
| **Not understood**: nothing matched | the capture, on this device | Needs a look, with the alert's text |

Nothing is guessed and nothing vanishes silently. When a bank changes its wording, its "not
understood" count rises on the Settings row and the Ledger's one card says so ("HDFC Bank's alerts
changed. 3 need a look."). A capture keeps the rule id and version that read it, so a fixed rule can
re-read the captures it missed.

**Fixtures.** `modules/app/src/commonTest/kotlin/ai/coffer/app/capture/Fixtures.kt`: one entry
per alert, with its sender, text and expected outcome and fields, kept as Kotlin so the same list
runs on every target (a resource file has no common reader). Personal details are made up; the
format is real. Phase 0 covers five banks; each format comes from published examples of that
bank's alerts, and the file says so, because none has yet been checked against the owner's own
phone. The owner adds real, redacted alerts during the checkpoint.

### 4.3 Dedupe: one payment, several alerts

Each read alert yields a **capture**: app, posted time, amount, direction, account hint (last four
of a card or account, or a VPA), payee as written, UPI reference (RRN, 12 digits) or transaction id
when present, and fee or balance if stated. A capture is matched against entries in a window around
its time:

| Signal | Confidence | What happens |
|---|---|---|
| Same RRN or transaction id | certain | merged into the entry |
| Same amount and direction, same account hint, within 30 min | likely | merged, and the entry is marked "From 2 alerts" with Split one tap away |
| Same amount and direction within 30 min, account unknown on one side; or amounts differing by a stated fee | possible | **not merged.** A new entry, plus a "Same payment?" suggestion in Needs a look: Merge / Keep both |
| Anything else | none | a new entry |

- An entry keeps the list of captures that made it. **Split** puts each back as its own entry; a
  **Merge** the user makes is just as reversible. Nothing is deleted by dedupe.
- The amount an entry shows is the largest stated amount among its captures when they differ by a
  stated fee (a bank alert that includes a fee the UPI app left out), and the entry says which.
- The window and thresholds are constants with tests, tuned at the checkpoint against real data.

### 4.4 Categories and rules learned from corrections

- Categories are a short default set (food, groceries, transport, bills, shopping, health, home,
  transfers to self, other), editable. "Transfers to self" is excluded from spending.
- **A correction teaches.** Setting an entry's category records a rule keyed by the payee's
  normalised form (lower case, punctuation and bank suffixes stripped) or, better, its VPA. The
  next capture from that payee is filed by the rule, and the entry says so in one line
  ("Filed by your rule: Swiggy → Food", EX2).
- After a correction, one tap offers the rest: "Also file 12 earlier Swiggy entries under Food?"
  (IN3, a decision made once above the rows).
- Rules are a list in Settings: edit, delete. The newest correction wins; a rule is never invented
  from anything but a correction or an explicit "always file X as Y".

### 4.5 Money, numbers and dates: into `reaktor-core`

**Placement.** No permission, no SDK; every platform already has a formatter (Android ICU, Apple
`NSNumberFormatter`, JS `Intl`, the JDK). So it is a package in an existing module, not
`reaktor-i18n`. The ladder's "module that owns the concern" is the base: `reaktor-core`, package
`dev.shibasis.reaktor.core.i18n`. `reaktor-ui` was rejected because CSV export and notification text
format money without drawing anything.

```kotlin
@Serializable data class Money(val minor: Long, val currency: String)   // never a Double
object Currencies { fun minorDigits(code: String): Int }                 // ISO 4217 table, common code
expect class NumberFormats(locale: String) {
    fun money(value: Money, display: CurrencyDisplay = Symbol): String
    fun decimal(value: Long, scale: Int): String
    val decimalSeparator: Char
    val groupingSeparator: Char
}
fun parseMoney(text: String, currency: String, formats: NumberFormats): Money?   // common code
```

- **Formatting is the platform's**, so lakh/crore grouping, symbol placement and digits are right
  for every locale without shipping CLDR. The one exception is the JVM: `java.text.DecimalFormat`
  uses only the last grouping interval, so `en-IN` prints `100,000` instead of `1,00,000`. The JVM
  actual regroups from the pattern's secondary grouping, with a test.
- **Parsing is common code**: what a person types ("1,23,456.78", "1.234,56", "١٢٣٫٤٥",
  "₹ 500") becomes minor units using the locale's separators, with every Unicode decimal digit
  normalised. Ambiguity is an error shown under the field, never a guess.
- **Dates:** an entry stores the instant (epoch ms, UTC) and the zone it happened in. Its day is the
  instant in that zone, so a 23:30 payment in Bengaluru stays on its day when read in London. Month
  totals key on that local day. Bank alerts' own dates ("09Nov26", "09-11-26") are read in the
  sender's zone (`Asia/Kolkata` for Indian banks).

### 4.6 Forms: into `reaktor-ui`

A form model with Compose bindings, package `dev.shibasis.reaktor.ui.forms`. No platform code.

- `FormField<T>`: text held in a `TextFieldState` owned by the node (the `reaktor-app` skill: no
  `StateFlow` round trip), a parser `String → T?`, validators, `touched`, and an error that appears
  **on blur or submit, never on each keystroke** (IN6).
- `Form`: its fields, `validate()`, `submit { }`, and a `FormDraft` hook (save and restore the raw
  text) that the app backs with its own store, so typed input survives the process being killed
  (ST4).
- `FormTextField(field, label, keyboard)`: the label always visible (a placeholder is not a label),
  the error under the field and in its semantics (`error(...)`, AC1), the right keyboard, autofill
  hints, no autocorrect on codes and amounts (the iOS lesson in `reaktor-app`).
- `MoneyField` composes `FormTextField` with `parseMoney` and a currency picker.

### 4.7 Scale: range reads into `reaktor-db`, measured with `reaktor-performance`

The object table's primary key is `(store_name, key)`, so a key range within a store is an index
scan. `ObjectDatabase` gains (built, `b2b5b082`):

```kotlin
suspend fun <T : Any> getRange(store: String, type: KClass<T>, serializer: KSerializer<T>,
                               from: String?, until: String?, limit: Int, descending: Boolean): List<StoredObject<T>>
```

A `count` was planned beside it and left out until something needs it.

with a default in the base class (getAll, filter, sort) so every backend keeps working, and a SQL
override. Coffer's entry keys sort by time (`<epoch ms, 13 digits>-<id>`), so "this month" and "the
next 50 older rows" are one range each. **Month totals** are a derived, device-local store updated
on every write and rebuildable from entries; Months reads twelve rows, not a year of entries.
**Search** scans payee and note on a background dispatcher over a cached index of distinct payees
(hundreds, not tens of thousands), then range-reads matches. If that misses the budget, SQLite FTS
is the fallback, and it is Cairn II's to build.

**The dataset and the budgets.** A seeded generator writes 5 years: ~8 entries a day (~15,000), ~2.4
captures each (~36,000), realistic payees and categories, a mix of currencies. Measured with
`ReaktorPerformanceHarness` on the JVM (SQLite) in CI and on the emulator, against:

| Operation | Budget (emulator, mid-range proxy) |
|---|---|
| First page of the Ledger (50 rows) from cold storage | ≤ 50 ms |
| A month's entries + its totals | ≤ 100 ms |
| Twelve months of totals | ≤ 30 ms |
| Payee search, any term | ≤ 300 ms |
| Cold start to the Ledger drawn | ≤ 2 s (PF2) |
| Rebuilding all month totals | ≤ 3 s, off the main thread, once per migration |

### 4.8 Migration: a runner in `reaktor-db`

`Migrations(database, steps)`: a schema version in a meta row, ordered steps run once each with
`Origin.Migration`, each step idempotent (a kill mid-step reruns it), the version written only after
its step. Phase 1 takes Coffer across one real bump (v1 → v2: `payee` split into `payeeAsWritten`
and the user's `payee`, and month totals rebuilt) and tests it both ways: a v1 database file in the
test resources upgraded on the JVM, and on the emulator a v1 build installed, used, then updated in
place.

### 4.9 Accessibility and localisation: the reference implementation

These are release blockers, not polish. Each has a check that is run, not assumed.

| Area | What is done | Checked by |
|---|---|---|
| Contrast (CO7) | every palette, every pair, asserted in `commonTest` on day one | the contrast test |
| Screen readers (AC1–AC4) | every control named; a ledger row read as one ("Swiggy, food, spent 250 rupees, 9 November"); amounts read as money, not digits | TalkBack on the emulator; VoiceOver on the simulator; the owner on a phone (AC4 needs a person) |
| Text size (TY4) | font scale 2.0 and iOS AX5; amount column reflows under the payee instead of clipping | screenshots of every screen at 2.0 |
| Reduce motion (MO9) | reaktor-ui's `LocalReduceMotion` read by every motion helper; the celebration skipped | animator scale 0, walk the app |
| Locales | `en-IN` (lakh), `en-US`, `de-DE` (comma decimal), `ar` (RTL, Arabic-Indic digits) | format tests on JVM, JS, iOS; RTL screenshots |
| Strings (CP8) | all copy in Compose resources, plurals through the resource system, no concatenation | the tells sweep and a pseudo-long-string pass |

What crosses into the framework from this, beyond §4.5–4.6: a `contrastRatio()` helper in
`reaktor-ui` (every app writes its own contrast test today), and GymBuddy's `Announce` for live
announcements (AC3), promoted rather than copied a third time.

### 4.10 Sync, tier 2: designed here, built only after the gate

Tier 1 (`Sync`, `SyncedStore`, `Hlc`, outbox, `HttpSyncTransport`, `MemorySyncLog`) keeps the latest
op per **object**. Two devices editing one entry offline, one the category and one the note, would
have the later write replace the whole object and lose the other edit. Tier 2 keeps the latest op
per **field**.

```
SyncOp(store, key, field: String?, value, hlc, device)   // field = null: a whole-object op (tier 1, unchanged)
server:   keeps the latest op per (store, key, field)      // MemorySyncLog and D1SyncLog: one more key column
client:   versions per (store, key, field)
FieldSyncedStore<T>(fields = listOf(FieldLens("category", …), FieldLens("note", …), …))
put(key, value) → diff against what is stored → one op per changed field, one HLC each
apply(op)       → read the object, set that field if op.hlc > its version, write with Origin.Sync
```

- **Deletion is a field** (`deleted: Boolean`), so delete and undo are ordinary per-field writes and
  a late edit cannot resurrect a deleted entry without a later "restore".
- **Creation** writes every field with one HLC.
- **Backwards compatible:** tier-1 stores and Hearth's server keep working; `field` is nullable and
  absent from old ops.
- **The test that defines done:** two sessions over separate databases and one in-memory server,
  both offline: device A edits entry 1, device B edits entry 2; then A sets entry 3's category while
  B sets entry 3's note. Reconnect in both orders. Every edit survives on both.
- Captures (raw alert text) never sync. Entries carry a small `evidence` field (app label and time
  per alert) so another device can show "From 2 alerts" without the text.
- Two Android phones capturing the same alert (a bank app signed in on both) is cross-device dedupe:
  entries carry the RRN when there is one, and a synced RRN index merges them. Phase 2 work, small.

### 4.11 Modules

| Module | Role | New or reuse |
|---|---|---|
| `reaktor-notification-listener` | `NotificationListener`: Android service, filter before copy; no-op elsewhere | **new module** (§4.1) |
| `reaktor-io` | template matcher, `text/` | **new files** |
| `reaktor-core` | `Money`, `Currencies`, `NumberFormats`, `parseMoney` in `i18n/` | **new files** |
| `reaktor-ui` | `forms/`; `contrastRatio`; `Announce` | **new files** |
| `reaktor-db` | `getRange`; `Migrations`; Phase 2: per-field sync | **change** |
| `reaktor-graph` | `ProcessGraph` | reuse (Hearth) |
| `reaktor-io` | `ShareAdapter` for the CSV | reuse |
| `reaktor-performance` | `ReaktorPerformanceHarness` for the dataset budgets | reuse, first honest workload |
| `reaktor-cloudflare` / `reaktor-auth` | Phase 2 only, per the gate's answers | reuse |

Not used: `reaktor-notification` (Coffer posts nothing in v1), `reaktor-media`, `reaktor-tactile`
(UI haptics go through Compose, per `reaktor-app`).

---

## 5. Data model

`ObjectDatabase`, one store per kind. Keys of time-ordered stores start with 13-digit epoch ms so a
range is a period.

```
Entry      key = <occurredAtMs>-<id>
           occurredAtMs, zone, amount: Money, direction (Out | In), payee, payeeAsWritten,
           category?, account?, note, excluded, origin (Alert | Manual), evidence[], rrn?,
           deleted, categorisedBy (You | Rule(id) | None)
Capture    key = <postedAtMs>-<id>          device-local, never synced
           app, sender, postedAtMs, text, outcome (Read | NotUnderstood), ruleId?, ruleVersion?,
           fields (amount, direction, account, payee, ref, fee, balance), entryKey?
Category   key = id      name, kind (Spend | Income | Transfer), order, archived
Rule       key = id      match (Payee(normalised) | Vpa(handle)), category, madeFrom (Correction | You), hits, updatedAtMs
Budget     key = <categoryId>   monthly: Money, from (yyyy-MM)
Account    key = id      label, institution, hint (last four or VPA), kind (Bank | Card | Wallet)
Suggestion key = id      device-local   kind (SamePayment(a, b) | FileEarlier(rule, n)), state
MonthTotal key = <yyyy-MM>|<category>|<currency>   device-local, derived, rebuildable
Counts     key = <app>   device-local   read, ignored, notUnderstood, since
Settings   device-local  currency, allow-list, appearance
```

**What syncs, per field (Phase 2):**

| Store | Synced fields | Not synced |
|---|---|---|
| Entry | occurredAtMs, zone, amount, direction, payee, category, account, note, excluded, deleted, evidence, rrn | `payeeAsWritten` stays with its captures on the capturing device; `categorisedBy` is recomputed |
| Category | name, kind, order, archived | — |
| Rule | the whole rule, last write wins (tier 1 is enough: one person edits a rule as a unit) | `hits` |
| Budget | monthly, from | — |
| Account | label, institution, hint, kind | — |
| Capture, Suggestion, MonthTotal, Counts, Settings | — | all: device-local or derived |

---

## 6. Design (house-style)

House-style was read in full before this section (standards, evaluate, deslop, platforms, the
GymBuddy example). Everything marked ◆ is a **proposal** until the owner approves it in `DESIGN.md`;
Phase 0 and 1 build with it so it can be judged on a screen, as Hearth's identity was.

**Subject's world (ID1).** Ledgers and the people who keep them: ruled columnar paper, amounts in a
right-hand column aligned on the decimal point, a single rule under a subtotal and a **double rule
under a closed total**, the auditor's tick against each verified line, a passbook's printed rows.
In Indian offices **green ink is the auditor's**: the verifying hand, distinct from the clerk's
blue-black. That is where the identity comes from, not from finance apps (no red/green up-down
arrows, no gradient card hero, no donut on the home screen).

| Choice | Proposal ◆ | Reason from the world | Rules |
|---|---|---|---|
| Accent | **Audit green**, a deep ink green at low-to-medium chroma (not emerald, not teal); fill, ink-on-fill and tint chosen per scheme | the verifying ink; it marks the primary action, the selection and live state, nothing else | ID2, CO3, CO4, CO12 |
| Grounds | light **Ledger** (pale grey-green paper, very low chroma; default) and **Passbook** (white); dark **Ink** (dark neutral grey; default) and **Carbon** (true black, OLED option) | paper and ink | ID2, CO6, CO10 |
| Amounts | never coloured by direction. Spent and received are told apart by sign, column and word, as a ledger does | red already means "can't be undone" (CO5); colour alone is not allowed (CO8) | CO5, CO8 |
| Over budget | ink plus a mark and words ("₹2,300 over"), no red, no scolding | FB7 | FB7, CO5 |
| The one bold move | **the ledger column**: every amount on every screen in one right-aligned tabular column, decimal points aligned, ruled rows | it shows on every screen; everything around it stays quiet | ID9, TY3 |
| Drawn thing | **the rules**: a single rule under each day's subtotal, a double rule under a month's total once the month is closed; the auditor's tick on entries Coffer read and you confirmed | the ledger's own marks replace "Status: reconciled" text | ID3 |
| Celebration | **Closing the month**: when the last entry of a finished month that needed a look is resolved, the double rule draws under its total (≈600 ms, the house curve), once per month, with the Success haptic. Skipped under reduce motion, and skipped if not seen within 2 s (FB8) | the peak and the end of the job Coffer gives you: the books for that month are done | ID4, FB3, FB8, MO9 |
| Type | system font (TY1); tabular figures on every amount (TY3); one display size, for the month's total | | TY1–TY3, TY7 |

**Everything else follows house-style as written**, and the plan names the rules so DESIGN.md and
the audit can match them:
- **Colour:** tokens only, every CO2 role, both schemes re-derived not inverted, the contrast test on
  day one over every ground × accent (CO1, CO2, CO6, CO7). Window and launch colours match the ground
  (CO9).
- **Layout:** 4-grid spacing, five radii, one primary action per screen (Add on the Ledger), home with
  one job plus at most one card, no stat strip, no cards in cards (LA1–LA3, LA7, LA10, LA11).
  Settings in order of use: notification access and the allow-list first, appearance last (LA12).
- **Motion:** 120 / 200 / 300 ms, the one curve, the one spring only for the tick landing, press on
  every target, reduce motion honoured in the motion file (MO1–MO4, MO9, MO11).
- **Haptics:** Tap when a category is picked; Success for closing a month; Warning before the one
  thing that can't be undone (erasing all data). No Alarm: nothing in Coffer is a timer (FB6).
- **States:** each screen designed for loading, empty, partial, error and ideal (ST1). Empty states
  written for their screen (ST2): the first run's Ledger says alerts will appear here as you pay and
  offers the one action, "Let Coffer read bank alerts…"; an empty search says nothing matched and
  what was searched; an empty month says no payments were recorded. Errors sit beside their cause
  and say what fixes them (ST3, CP3). Typed input survives everything (ST4). Undo, not confirm, for
  delete, split and merge (IN4); the undo holds 10 s and finishes when the screen goes (FB5, IN13).
- **Copy:** words from the world and the user's side: "Spent", "Received", "Needs a look", "From
  2 alerts", "Split", "Filed by your rule". Never "transaction", "sync", "parse", "notification
  listener" in the UI (CP1). Sentence case, verbs on buttons (CP2), status copy in parallel ("Reading
  alerts…", "214 alerts read", "3 alerts not understood. Check the wording.") (CP10), none of the
  CP9 tells.
- **Teaching:** no tips in the first session; the "i" beside the month total explains what is
  excluded (transfers to self) (EX1, EX5). Budgets and the Months screen wait for a month of data
  (EX6).

**Departures from house-style, proposed:** none. If the owner rejects Audit green, the fallback is
blue-black register ink, which is closer to the finance-app median and would be recorded as such.

---

## 7. Play policy, data safety, disclosure (from `reaktor-ship`)

- **Permissions you can defend.** `BIND_NOTIFICATION_LISTENER_SERVICE` (on the service, the reason
  for the app); `POST_NOTIFICATIONS` only if a later phase posts anything, and not merged in from
  `reaktor-notification`, which Coffer does not depend on. No SMS permission in v1. `INTERNET` only
  from Phase 2. Checked in the merged manifest, not the written one.
- **Data safety, v1:** nothing collected (nothing leaves the device). Stored locally: financial info
  (payments, amounts, payees), and the text of alerts from the allow-listed apps. Phase 2 changes
  this answer, and the gate decides how (end-to-end encryption or not).
- **Backups (Phase 1.5, package D).** The backup file and the weekly copy go only where the person
  chooses through Android's own picker; Coffer sends nothing. Android's own backup is opt-in and off
  by default (`allowBackup` with `CofferBackupAgent`): to the cloud only when switched on and the
  transport encrypts on the phone, phone-to-phone transfer always. Looked up 11 November: Google
  says Android's backup is end-to-end encrypted with the screen lock and defines "collection" as
  the app sending data off the device; no Play text found says how an app's Android-backup data is
  declared. Settle it before release, from the Play Console's own help.
- **Prominent disclosure** before the access screen (§4.1), and a privacy policy URL, which Play
  requires for an app reading this class of data even with no server.
- **Android 13+ restricted settings.** On a sideloaded install, Android can block granting
  notification access until the user allows restricted settings in App info. Checked in Phase 0 on
  API 36: a build installed with `adb install` is not restricted; the switch in Settings is enabled
  and the grant goes through. An APK opened from a browser or a file manager is the case that is
  restricted, and the owner's checkpoint build should be installed with adb. Play installs are not
  affected.
- **What Android tells the person.** The system's own grant dialog says the app "can read your
  notifications, including personal information such as contacts, messages, and photos." Coffer's
  disclosure has to say, before that, how much less it does.
- **If the gate picks the SMS route:** the Permissions Declaration Form under "SMS-based money
  management", a review, the listing describing SMS reading as the core feature, and the rule that
  non-financial SMS never leaves the device. The exception is temporary by Google's own wording.

---

## 8. Phases

**Phase 0: spike (Android emulator).**
- Framework: `reaktor-notification-listener` (Android listener, filter-before-copy, no-op actuals,
  tests); the template matcher in `reaktor-io`; `Money`, `NumberFormats`, `parseMoney` in
  `reaktor-core`; `getRange` in `reaktor-db`. Common code tested on JVM, JS and the iOS
  simulator.
- App: the repo stood up (§9), `DESIGN.md` draft, colour tokens and the contrast test (house-style's
  new-app order), the session graph, Capture, five sender formats with fixtures, and one Ledger list.
- Proves: alerts posted with `adb shell cmd notification post` reach the list; a non-allowed app's
  alert leaves no trace; the five formats parse; an unparsed alert lands in the count; amounts print
  correctly in `en-IN`, `en-US`, `de-DE` and `ar`.

**Phase 1: an offline app.**
- Ledger, Entry, Needs a look, Months, Add/edit (through `reaktor-ui` forms), Settings; categories;
  learned rules; dedupe with Split and Merge; budgets; CSV export.
- `Migrations` and one real schema bump; the 5-year dataset and the §4.7 budgets, measured.
- The a11y/i18n pass of §4.9; the house-style pass; `DESIGN.md` final; `/house-style evaluate` then
  `/house-style deslop`, gaps fixed or recorded in `docs/house-style-audit.md`.
- iOS build with manual entry, every screen opened on the simulator.
- *Checkpoint:* the owner's only expense tracker for two weeks, with real alerts. Measures how many
  payments each route saw and missed.

**Phase 1.5: closing the gaps users complain about (§2.5), before the checkpoint.** Packages from
`coffer/docs/user-grievances.md`, the owner choosing which:
- A · Nothing stops silently: per-sender health ("HDFC Bank: no alerts for 9 days"), phone-maker
  battery and autostart guidance at the moment the listener dies, the wording-change card.
- B · Spending means spending: card bill payments, ATM withdrawals and wallet top-ups filed as
  movements between one's own accounts; reversals and refunds matched to the payment they undo.
- C · Sensible from day one: starter payee rules for the merchants Indians pay most, overridable.
- D · Never lose a year: backup file and restore through Android's picker, weekly to a chosen
  folder; Android's own encrypted backup only as an opt-in, if the owner agrees.
- E · Cash in two taps. F · Import from Money Manager and Axio exports (needs sample files).
- G · Free, and staying free: a written promise, if the owner makes it.

**GATE. Stop and ask the owner. Phase 2 does not start on a default.**
- **G1. Capture route:** bank and UPI apps only; plus the Messages app's notifications (policy risk,
  §4.1); or the declared `READ_SMS` route with its form and review.
- **G2. Encryption:** end-to-end encrypt synced records before any financial data reaches a Worker
  (`reaktor-security` or a simpler per-account key), or not.
- **G3. Server:** Coffer's own Worker + D1, or a store on Hearth's account Worker.
- **G4. Platforms** beyond Android and iOS.
- Also for approval: the identity in `DESIGN.md` (◆), and the name (§13).

**Phase 2: sync tier 2.** Per-field ops on the durable outbox and per-field merge in `reaktor-db`
(§4.10), the server log's extra key column, the two-device test, then the app's SyncNode. Anything
that creates Cloudflare resources waits for a yes.

**Phase 3: reach.** iOS capture through Shortcuts / App Intents (needs reaktor App Intents),
desktop and web shells, more banks.

---

## 9. Copied by hand: the input for `reaktor-cli new`

Updated as Phase 0 lands. Same columns as hearth.md §7.

Everything below was copied from Hearth (`hearth@60f56d0`), the most recent app on this branch.

| # | File | What was copied, and what had to change by hand |
|---|---|---|
| 1 | `settings.gradle.kts` | Hearth's whole, with the worker include dropped and `reaktor-notification-listener` added to the `dependencySubstitution` list. **The list is hand-kept and drifts**: every new reaktor module means an edit here in every app that uses it. |
| 2 | `build.gradle.kts` | Verbatim, including the pnpm workspace setup Coffer does not need yet (no JS target); kept so the file does not diverge. |
| 3 | `gradle.properties` | Verbatim (`reaktorVersion`, AGP 9 flags, `skipIosRelease`). |
| 4 | Gradle wrapper | `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (Gradle 9.7). |
| 5 | `.gitignore` | Hearth's, minus the worker lines. |
| 6 | `local.properties` | `sdk.dir` and the CocoaPods path. Per machine, not committed. |
| 7 | `modules/app/build.gradle.kts` | Hearth's shape: compose-library plus the cocoapods plugin, the podspec block (the `sqlite3` link line is still required). Dependencies chosen per app. |
| 8 | `modules/app/src/androidMain/AndroidManifest.xml` | Empty of permissions: Coffer has none of its own. |
| 9 | `targets/appAndroid/build.gradle.kts` | Verbatim except the application id and the namespace (`ai.coffer.android`, which AGP 9 makes differ from `:app`'s). |
| 10 | `targets/appAndroid/src/main/AndroidManifest.xml` | The Application class and launcher Activity, plus two app-specific edits a template should know about: `tools:node="remove"` on `INTERNET` (arrives from reaktor-io), and `tools:replace="android:label"` on the listener service so Settings says what Coffer reads. |
| 11 | `targets/appAndroid/src/main/res/values{,-night}/themes.xml` | NoActionBar theme; the window ground typed in by hand to match `Theme.kt` (CO9). |
| 12 | `targets/appDarwin/` | Not yet: Phase 1 with the iOS build. |
| 13 | Entry points | `CofferApplication` (adapter order Dependency, File, Sql, Database; then the listener slot; then the session), `CofferActivity`, `CofferViewController.kt`. Same shape as Hearth's; only the capabilities differ. |
| 14 | Design starter | `design/Theme.kt`, `Tokens.kt`, `Motion.kt` (the house press), the contrast test, `DESIGN.md`: shape from Hearth, values new. |
| 15 | Test scaffolding | A session test needs `Feature.Dependency = KoinDependencyAdapter(koinApplication { })` and an in-memory database, both reset after. Every app copies this. |

What the template should take from this: items 1, 3 and 11 drift silently (the substitution list,
the versions, the window colour); 10 and 13 differ per app but share a shape; 15 belongs in a test
helper in reaktor-graph rather than in each app.

---

## 10. Reuse contract

- **The process scope:** Hearth's `ProcessGraph` and Context adapters, as they are.
- **Sync:** tier 1 as built; tier 2 extends it in place, and Hearth's stores keep their behaviour.
- **The suggestion policy** for uncertain links: Cairn §8's, applied to payments.
- **Template matcher:** available to Lector's grammar; Lector is not migrated by this work.
- **Forms, `Money`, `NumberFormats`, range reads, `Migrations`:** available to every app; none is
  migrated by this work.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| Notification-only misses SMS-only alerts, so Coffer under-counts and loses trust | per-app counts on screen; the checkpoint measures the gap; G1 decides the route with numbers |
| Play rejects the listener use, or reads the Messages-app route as SMS circumvention | v1 on Play reads bank and UPI apps only; explicit disclosure and consent; the SMS route is a gate decision, not a default |
| Android's OTP detector hides real transaction alerts | redacted alerts land in Needs a look, counted; measured on a real Android 15+ phone at the checkpoint |
| A bank changes its wording | "not understood" counts per sender, on the Settings row and as the Ledger's one card; fixtures make the fix a test |
| A wrong merge loses a payment | only an exact reference merges silently; likely merges are marked and split in one tap; possible ones are suggestions |
| Money rounding or locale bugs | integer minor units only; parsing in common code, tested on three targets; formatting tests per locale per target |
| `ObjectDatabase` too slow at 5 years | range reads on the primary key; derived month totals; budgets measured, with FTS as the named fallback |
| Two devices' edits to one entry lose one | tier 2 per-field ops, and the two-device test that defines done |
| Financial data on a server | nothing leaves the device before the gate; G2 asks about encryption first |

---

## 12. Open decisions

| # | Decision | Options | Default if unresolved | Needed by |
|---|---|---|---|---|
| 1 | Capture route (G1) | apps only / + Messages notifications / declared SMS | apps only on Play; owner's build may add Messages for the checkpoint | Gate |
| 2 | E2E encryption of synced data (G2) | MLS via `reaktor-security` / per-account key / none | none built; no data leaves before the answer | Gate |
| 3 | Sync server (G3) | own Worker + D1 / Hearth's Worker | — | Gate |
| 4 | Platforms beyond Android and iOS (G4) | desktop / web / none yet | none | Gate |
| 5 | Identity ◆ | Audit green on Ledger/Ink / register blue-black / other | Audit green, as proposed | before Phase 1 ships |
| 6 | Dedupe window | 10 / 30 / 60 min | 30 min, tuned at the checkpoint | Phase 1 |
| 7 | Name | Coffer / other | see §13 | before a store listing |
| 8 | Phase 1.5 packages | A–G of `user-grievances.md` | **taken: A, B, C, D, E, F** (owner, 10 Nov 2026) | before the checkpoint |
| 9 | Android's own cloud backup | opt-in / off | **taken: opt-in, said plainly** (owner, 10 Nov 2026) | with package D |
| 10 | The free promise (G) | promise in app and listing / no promise yet | **taken: not yet** (owner, 10 Nov 2026) | before a store listing |

---

## 13. The name

Checked 9 November 2026 with one web search: no app called Coffer turned up on the App Store or Play
for budgeting. A Play developer named "COF BUI" publishes an expense tracker, apparently unrelated.
Not checked: an exact-name search inside each store, trademarks. The package is `ai.coffer.app`
until the name is settled.

---

## 14. Status

**Plan written, 9 November 2026** (`a737c0e1`). Research taken as given from the brief (competitors,
capture routes, Account Aggregator). The two CHECK items were looked up the same day (§4.1): Android
15 redaction is real and keyed on detected OTPs; no Google text names notification listeners in the
SMS policy, and the Messages-app route is treated as a gate question.

**Phase 0 — done, 9 November 2026.** Framework: `975e9437` (`Money`, `NumberFormats`, `parseMoney`),
`1482ff9a` (templates), `b2b5b082` (`getRange`), `5b74921b` (`reaktor-notification-listener`). App:
`coffer@779fb41`, `coffer@fe7713f`.

| Check | Where | Result |
|---|---|---|
| `Money`, `NumberFormats`, `parseMoney`: 19 tests, including every printed amount reading back across 11 locales × 6 currencies | JVM, Node, iOS simulator | pass on all three |
| Templates: 12 tests | JVM, Node, iOS simulator | pass on all three |
| `getRange`: SQLite and in-memory agree; a 50-row page of 20,000 rows costs under a tenth of the store | JVM | pass |
| Listener gate: a filtered-out notification is never read | JVM, iOS simulator | pass |
| Coffer: 12 alert fixtures (5 banks, OTPs, a reworded alert, an offer), capture end to end, contrast | JVM (Android unit tests) | pass |
| Alerts posted with `cmd notification post` become entries | Android emulator, API 36 | yes: HDFC, ICICI, Kotak read; the OTP skipped and not stored; a reworded HDFC alert and a chat message kept as not understood |
| Nothing from an app outside the filter is kept | emulator | yes: the shade held Android, Play Store, Wellbeing and Safety Center notifications; the database held only the shell's |
| Amounts in the phone's locale | emulator, per-app locale | `en-IN` ₹65,500.50 and ₹64,000.00; `de-DE` 64.000,00 ₹; `ar-EG` mirrored layout and ₹ ٦٤٬٠٠٠٫٠٠ |
| Granting access through Settings, adb-installed build | emulator | not restricted; the system's dialog and grant work |
| Reconnecting re-reads the shade | emulator | it did, and over-counted a skipped OTP; fixed (`coffer@fe7713f`) and re-checked over four reconnects |

How it came out, against the plan:
- **iOS simulator tests are switched off on this branch** by dependeasy (`PhysicalDevices.kt`:
  "builds never boot simulators"). They were run with a scratch Gradle init script that re-enables
  the compile, link and test tasks for one invocation, each task in its own Gradle run because the
  cleared dependencies fail Gradle's validation when run together. The branch was not changed.
- **The listener module has no web target.** Adding one changed the pnpm lockfile every JS build in
  reaktor shares, while another session had uncommitted lockfile changes of its own. Nothing on the
  web can listen, so the target waits for an app that has a web shell.
- **Android 15 OTP redaction did not trigger on the emulator**, which runs Android System
  Intelligence: an OTP posted from the shell reached Coffer with its text. That says nothing about
  a phone: the real-device check in §4.1 stands.
- **Coffer ran on its own emulator** (a `Coffer_Phone` AVD on port 5560, headless), because
  emulator-5554 had Lector in front and 5556 is Hearth's.

Not done in Phase 0, carried to Phase 1: the iOS app shell (the common code compiles for iOS);
dates, month names and times in the phone's locale (English and hand-built today); the ₹0.00 total
on day one (FB7, EX6); dark scheme, font scale 2.0 and TalkBack not yet looked at.

**Phase 1 — built, 9–10 November 2026; at the checkpoint.** Framework: `d41ef983`
(`DateFormats`), `983dc117` (forms), `db9a8fa2` (`Migrations`), `ddf30630` (`contrastRatio`),
`1fc19e77` (`Money.decimalString`), `ff3c99f9` (`getContaining`). App: `coffer@a9e42ef` through
`coffer@` the iOS commit. 19 app tests on the JVM.
- Built: categories, rules learned from corrections, dedupe with split and merge, Needs a look,
  entry, add/edit on reaktor-ui forms with drafts, months with budgets and the closing double rule,
  settings, CSV export, search, the disclosure, schema v2 through `Migrations`, the iOS app.
- Checked on the emulator: the Phase 0 ledger migrated in place; a correction filed the next alert;
  five seeded years: cold start 585–690 ms, a month 7–9 ms warm, search 42–135 ms (was 1.2 s on the
  main thread before `getContaining` and moving ledger work off it); font scale 2.0, dark, `ar-EG`
  RTL. Checked on the iOS simulator: manual entry end to end.
- 10 November, on the emulator: IN14 fixed (the four categories used most, then "More
  categories…", `coffer@164e484`); TY4 at font scale 2.0 on every screen; ST4 with the process
  killed mid-typing; the closing double rule drawing once (September) and drawn at once with
  animations off (August); a one-frame flash of the last month's numbers under a new month's title
  fixed (`coffer@945f1df`, and house-style ST7). Filing one payee's 514 entries from the offer
  closed every past month in one tap.
- **Phase 1 is built; its checkpoint is not.** Left for release, not for the gate: CP8 (copy into
  Compose resources, needed before any translation), AC4 (a person walks the app with TalkBack and
  VoiceOver), iOS AX5, the closing haptic on a phone, the identity's approval (◆). Left for the
  checkpoint: two weeks as the owner's only tracker on a real phone with real alerts, which also
  checks the five bank formats, the Android 15 OTP redaction (§4.1) and the dedupe window. The gate
  (§8) waits on that, and Phase 2 has not started. See `coffer/docs/house-style-audit.md`.

**Phase 1.5 — built, 10 November 2026; at the checkpoint.** The grievance research
(`coffer/docs/user-grievances.md`, `coffer@6837da2`; 6,053 App Store reviews of 13 apps; Reddit and
Play not read) and the owner's picks: A, B, C, D, E, F; Android backup opt-in; no free promise yet
(§12 rows 8–10). G was not built.
- Framework: `6de977a6` (listener: battery restriction, the maker, the keep-alive screens),
  `7869d36a` (`reaktor-io`: `FolderChooser`, `FolderWriter`, `DocumentSaver`), `f282a77c`
  (`importRaw` in one transaction), `3bf2ccaf` (`deleteAll`). House-style: `skills@f08bd18`
  (EX7, IN17, CP12).
- App: C `coffer@77026b9`, B `coffer@b3c5634`, A `coffer@29d27d9`, D `coffer@a9b4658`, E
  `coffer@5b05ff6`, F `coffer@dfed312`, a day-heading fix `coffer@f04f335`, the checkpoint's
  evaluate and deslop `coffer@ed9203d`. 46 app tests on the JVM; reaktor-db 77 on the JVM and 28 on
  Node; the app and the new framework code compile for the iOS simulator.

| Check | Where | Result |
|---|---|---|
| C: a Zomato alert filed Food and drink on day one; the person's own rule wins | emulator; JVM | yes |
| B: a payment and its refund (same UPI ref), an ATM withdrawal, a card bill | emulator, `cmd notification post` | refund linked ("₹180.00 refunded"), ATM under Cash withdrawals, card bill between own accounts; the month's spending unchanged |
| A: Keep Coffer running opens Android's battery list; back, it says which list | emulator (Pixel image) | yes; makers' own screens need their phones |
| D: folder chosen once, first copy written | emulator, Documents | 6.5 MB for five seeded years |
| D: restore five years, then Undo | emulator | 15.5 s before `f282a77c`, 1.6 s after; Undo put the old ledger back |
| D: save-as reports the name Android chose | emulator | "coffer-backup-2026-11-10 (1).json" |
| D: the folder deleted | emulator | said on Backups and as the Ledger's card |
| D: Android backup, `bmgr` with the local transport | emulator | off + encrypted: nothing sent; on + encrypted: backed up; on + unencrypted: nothing; off + phone-to-phone: backed up |
| E: Cash, 120, Groceries | emulator, font 1.0 and 2.0 | saved in three taps; the month line shows ATM cash against cash paid |
| F: an HDFC-style statement CSV | emulator | 3 payments previewed, the summary line left out by number, dates asked (all days ≤ 12), added in 66 ms, Undo |
| Scale: backup and restore of five years | JVM, SQLite file | 49 ms, 7 MB; restore 296 ms (4.4 s before `f282a77c`) |
| House-style at the checkpoint | emulator, 1.0/2.0, light/dark | six findings fixed (EX7, IN17, CP12, ID7, a crowded column list, ST3); see the audit |

How it came out, against the plan:
- **F was built without sample exports.** Money Manager and Axio files were not to hand, so the
  importer guesses columns from their names and was tested on files written to their known
  shapes and on Coffer's own CSV. Real exports from the owner would settle it.
- **A restore replaces what is on the phone**, against house-style IN10's "beside, never over".
  Recorded in `coffer/DESIGN.md` as a proposed departure, for the owner at the gate.
- **The framework fixed a cost that every app paid:** `importRaw` was one commit per row. Coffer's
  seeding got ten times faster with it.

**Continued, 11 November 2026.**
- **Money Manager exports Excel, not CSV** (headers Period, Accounts, Category, Subcategory,
  Amount, Income/Expense, Note, Description; dates as Excel day numbers; from a third-party
  analyzer of its exports and Realbyte's import template, since Realbyte's help pages refuse
  automated reads). Framework: `bd8d50de` (`reaktor-io`: `Inflate`, `Zip`, `Xlsx` in common code;
  tests pass on the JVM, Node and the iOS simulator, and against the JDK's Deflater up to 1 MB).
  App: `coffer@4b6b7c7` reads `.xlsx`, keeps transfers apart, and files "Food" and "Salary" under
  Coffer's own categories. Checked on the emulator and the iOS simulator. Axio's export format
  was not found.
- **iOS:** the share sheet and the Files picker were never installed, so backup and import could
  not work on an iPhone; fixed and used on the simulator (`coffer@ccb4309`). AX5 walked on five
  screens; a title that broke mid-word fixed in `TopBar`.
- **TalkBack** heard on the emulator: action labels were nouns ("Double-tap to Back"); fixed
  (`coffer@9671a3c`, house-style AC1). The walk itself still needs a person: taps from adb under
  TalkBack sometimes activate instead of focusing.

Unchanged from Phase 1: the checkpoint is two weeks on the owner's phone, then the gate (§8).
Phase 2 has not started. Also left: AC4 (TalkBack and VoiceOver walks, now over the new screens
too), CP8, iOS AX5, the maker-specific keep-alive screens on real phones, the identity (◆).

Related: [app-portfolio.md](app-portfolio.md) · [hearth.md](hearth.md) · [cairn.md](cairn.md) ·
[lector.md](lector.md)

Sources for §4.1 and §7: [Android 15 behaviour changes, all apps](https://developer.android.com/about/versions/15/behavior-changes-all) ·
[Play: use of SMS or Call Log permission groups](https://support.google.com/googleplay/android-developer/answer/10208820) ·
[Play: SMS and Call Log permissions](https://support.google.com/googleplay/android-developer/answer/9047303) ·
[Play Protect developer guidance](https://developers.google.cn/android/play-protect/warning-dev-guidance?hl=en) ·
[KDE Connect on Android 15](https://bugs.kde.org/show_bug.cgi?id=495146)
