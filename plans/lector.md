# Lector — design & implementation

*Working name · read-aloud, voice-controlled PDF reader · September 2026 · plan doc for
[app-portfolio.md](app-portfolio.md) idea 10. Rename before it ships.*

Point it at a PDF and it reads aloud, hands-free. Talk back — *"highlight every date,"* *"note: check
this figure,"* *"next page"* — and it highlights, annotates and scrolls itself, following the spoken
word. Three interactions, all by voice: **read**, **mark**, **annotate** — plus **follow**.

---

## 1. Why this is idea 10 and not a feature

This portfolio killed a ten-idea draft for "features wearing app costumes," so a tenth idea owes the
costume test up front. Most of a read-aloud reader is already owned:

| Piece | Already owned by |
|---|---|
| Read-later / retained reader | Cairn arc II |
| Media playback, long-lived out-of-route scope | Podcast |
| Inbound share / OS ingestion (`ShareReceiver`) | Cairn arc I & II |
| Speech **recognition** (STT) | Recorder phase 1 |
| Mic permission | existing `PermissionAdapter` (`speech` constant) |

What nothing owns is exactly three forces, and they are the entire reason this stands alone:

1. **The synthesizer half of `reaktor-media/speech`.** `SpeechSynthesizer` is a `commonMain` stub
   with an empty body and no Android or Darwin implementation. Recorder forces the *recognizer* impl
   and never needs to speak; Lector is the first thing that does. Together the two make
   `reaktor-media/speech` real in both directions.
2. **Full-duplex voice control (barge-in).** Speaking while listening for a command, ducking or
   pausing the synthesizer the instant the mic opens, resuming cleanly after — two half-duplex OS
   engines contending for one audio focus. No other app in the portfolio holds capture and output at
   once. This is what makes Lector an assistant rather than a play button.
3. **Reading-follow.** Both OS engines emit a word range as they speak. That callback drives the
   highlight and the auto-scroll for free — no cloud TTS, no per-character bill. The work is mapping
   that range onto the rendered PDF and keeping the viewport ahead of the voice.

Everything else is **deliberate reuse**, named here so it isn't re-costed later (§9).

---

## 2. Scope

**In, v1:**

- Local PDFs (opened in-app, or handed in by share — §9).
- **On-device** TTS (OS engines) and **on-device** STT (push-to-talk).
- A **literal** command grammar: read/navigate/highlight/note (§8).
- Highlights and notes persisted and reopenable.
- Word-synced highlight + auto-scroll.
- Both platforms, one Compose Multiplatform graph.

**Out, v1 (and where each lands):**

- Cloud TTS / voice cloning — not needed; OS voices carry v1.
- Semantic commands ("highlight the argument, not the examples") — phase 2, LLM in a worker.
- Wake word / always-listening — phase 2; v1 is push-to-talk.
- Ambient "read any app on screen" — Android-only stretch, phase 3 (§10), iOS excluded.
- EPUB / DOCX / web article — phase 2; PDF is the v1 surface the user asked for.
- Cross-device sync of highlights/notes — later, on the shared sync substrate ([app-portfolio.md](app-portfolio.md) §6).
- Scanned (image-only) PDFs — OCR fallback noted §5.4, not v1.

---

## 3. Product surface

One screen. PDF page render, a text/highlight overlay, a transport bar (play/pause, speed, page),
and a **mic button** (hold to talk). While reading: the current word is highlighted and the page
auto-scrolls to keep it in view; when the last visible line finishes, the page advances. Holding the
mic **ducks then pauses** the voice, shows a live transcript of the command, executes it, and resumes
reading where it left off. Highlights and notes render in place; note markers sit in the margin.

---

## 4. Architecture

### 4.1 The reading loop

```
PDF → extract text + per-word boxes → segment into sentences
    → SpeechSynthesizer.speak(sentence)
    → onSpokenRange(charStart, charEnd)          // OS callback, per word
    → map char-range → word box (page, quad)
    → highlight quad + scroll viewport ahead
    → sentence done → next; page done → advance
```

The synthesizer is the clock. Nothing polls; the OS range callback is the single event that moves
the highlight, the scroll and the position cursor.

### 4.2 The command loop (full-duplex)

```
mic down → duck synth → after 150ms pause synth
        → SpeechRecognizer partial + final results
        → intent parse (grammar, §8)
        → apply (highlight / note / navigate / transport)
mic up  → resume synth from saved cursor (unless the command moved it)
```

The hard artifact here is a small **audio-focus state machine**: `Reading → Ducked → Listening →
Applying → Reading`, with the OS interrupt (a call, another app grabbing audio) as a first-class
transition. This is the one genuinely new muscle in the app (§11).

### 4.3 Speech adapters — the new framework work

`reaktor-media/speech` already defines the shape: an `Adapter<Controller>` bound to a `Feature` slot
via `CreateSlot`. `SpeechRecognizer` is stubbed; `SpeechSynthesizer` is stubbed the same way. Lector
writes the synthesizer bodies:

| Target | Synthesizer | Word-range callback |
|---|---|---|
| Android | `android.speech.tts.TextToSpeech` | `UtteranceProgressListener.onRangeStart(id, start, end, frame)` |
| Darwin | `AVSpeechSynthesizer` | delegate `willSpeakRangeOfSpeechString` (`NSRange`) |

Surface both as one common `Flow<SpokenRange>` off `SpeechSynthesizer`, so the reading loop is
platform-agnostic. Add `Feature.SpeechSynthesizer by CreateSlot<…>()` beside the existing
`Feature.SpeechRecognizer`.

For the recognizer: **reuse Recorder's impl** if it has landed (`SFSpeechRecognizer` / Android
`SpeechRecognizer` behind the stub). If Lector starts first, build only the push-to-talk subset it
needs and let Recorder generalize it later — the interface is already common, so this is not a fork.

### 4.4 PDF render + text layer

- **iOS:** `PDFKit` — `PDFDocument`, `PDFPage.selection(for:)` and `characterBounds(for:)` give word
  rects directly. Clean.
- **Android:** the built-in `PdfRenderer` rasterizes pages but exposes **no text layer**. Word boxes
  need a text-extracting lib (PdfBox-Android / iText-style) or Pdfium with an extraction shim. This
  is the asymmetric cost and an open decision (§12).
- **Scanned PDFs** (image-only, no text) need OCR — reuse `reaktor-media`'s camera/vision OCR path
  (ML Kit / Vision) to synthesize a text layer. Deferred past v1.

### 4.5 Intent parsing — two tiers

Split the way sync does, so the cheap tier ships first:

- **Grammar tier (v1, on-device):** a small finite grammar; targets resolved by fuzzy-matching the
  spoken phrase against the *visible* extracted text. Zero network, zero cost.
- **Semantic tier (phase 2):** utterances the grammar rejects go to an LLM in a `reaktor-cloudflare`
  worker that returns a structured command over the current page text. Only this tier needs a
  backend at all.

### 4.6 Modules

| Module | Role | New or reuse |
|---|---|---|
| `reaktor-media/speech` | `SpeechSynthesizer` impls + `Flow<SpokenRange>`; recognizer subset | **new** (the app's reason to exist) |
| `reaktor-media` (pdf) | PDF render + word-box extraction, OCR fallback | **new-ish** (no PDF today) |
| `reaktor-ui` / `compose-flow` | reader screen, highlight overlay, transport | reuse |
| `reaktor-db` | highlights, notes, reading position (SQLDelight) | reuse |
| `reaktor-core` `PermissionAdapter` | mic grant (`speech` already modeled) | reuse |
| `reaktor-io` `ShareReceiver` | open a PDF shared in from any app | reuse (Cairn builds it) |
| `reaktor-cloudflare` | semantic-intent worker | reuse, **phase 2 only** |
| Podcast player-scope pattern | long-lived reading session surviving nav | reuse (pattern, not code) |

No `reaktor-work` in v1 — there is no background queue; reading is foreground and synchronous.

---

## 5. Data model

SQLDelight in `reaktor-db`:

```
Document(id, sourceUri, contentHash, title, pageCount, addedAt)
Highlight(id, docId, page, charStart, charEnd, quads, color, origin{voice|touch}, createdAt)
Note(id, docId, anchorCharStart, anchorCharEnd, page, text, origin, createdAt)
ReadingPosition(docId, charOffset, page, updatedAt)   // one row per doc, last-write-wins
```

**Anchor stability** is the one modelling decision that matters: a highlight is stored as both a
character range in the extracted text *and* the resolved page quads. On reopen, re-resolve the quad
from the char range (text extraction is deterministic per lib/version); the stored quads are a
fallback and a render cache. Char-offset is the anchor, quads are derived — so a re-extraction with
better reading-order doesn't orphan old highlights. See §12.

---

## 6. Voice command grammar (v1)

| Say | Intent |
|---|---|
| "read" / "pause" / "resume" / "stop" | transport |
| "next" / "back" / "next page" / "go to page 12" | navigate |
| "faster" / "slower" | rate |
| "highlight `<phrase>`" | find phrase on visible page, highlight it |
| "highlight this sentence" | highlight the sentence being spoken |
| "highlight from here to `<phrase>`" | range highlight from cursor |
| "note `<text>`" / "note here" | attach a note at the cursor |
| "define `<word>`" | inline definition (on-device dictionary; iOS `UIReferenceLibrary`) |
| "repeat" / "again" | re-read last sentence |

Rules: any speech (mic down) **pauses** reading — barge-in is the default, not a mode. Ambiguous
targets ("highlight photosynthesis" when it appears five times on the page) resolve to the nearest
occurrence to the reading cursor, then "next one" walks them. Nothing here is destructive, so no
command needs a confirm step in v1; deleting a highlight ("remove that") is phase 2.

---

## 7. Reuse contract

Stated explicitly so nothing below gets counted as new work later:

- **Ingestion** is Cairn's inbound `ShareReceiver` ([cairn.md](cairn.md) §7) — share a PDF from any
  app into Lector. This is the cross-platform, store-safe version of "read any app"; the ambient
  overlay (§10) is the other, lesser version.
- **The reading session** is Podcast's out-of-route long-lived scope — the synthesizer must survive
  navigation, so it cannot live in a route graph. Same problem Podcast already solves; reuse the
  pattern.
- **Mic permission** is the existing `PermissionAdapter`; build the rationale UI, not the adapter.

---

## 8. Implementation phases

**Phase 0 — spike (2–4 days).** One PDF, rendered. Synthesizer reads it. Word highlight + auto-scroll
off the range callback. **No voice-in.** This proves the reading loop and the range→quad mapping —
the only parts with unknowns — before any command work.

**Phase 1 — the app (1–2 weeks).** Full-duplex command loop + audio-focus state machine. Literal
grammar (highlight / note / navigate / transport). Persist highlights, notes, position. Both
platforms. This is a shippable app.

**Phase 2 — reach.** Semantic intent via the worker; wake word; EPUB/plain text; share-in wired to
`ShareReceiver`; "remove that" and edit.

**Phase 3 — ambient (Android only, gated).** `AccessibilityService` + overlay bubble reads whatever
app is in front. iOS forbids it; it can neither highlight-in-place nor reliably scroll a foreign app;
Play Store polices accessibility use hard. A power feature, never the MVP, never promised on iOS.
Gate on a policy check (§12).

---

## 9. Feasibility

**High.** This is an integration app, not a research one:

- **No model training, no cloud dependency, no per-character cost, works offline.** The OS gives
  neural-enough voices, speech recognition, and — critically — the word-range callback that makes
  highlight and auto-scroll fall out for free. The expensive parts of a Speechify (ingestion
  pipeline, cloud-TTS unit economics, cross-app scraping) are all out of v1 scope by design.
- **Reuse-heavy.** Four of the six subsystems already exist in the framework or a sibling app.
- **One genuinely new muscle:** the full-duplex barge-in state machine (§4.2). That is days of
  careful platform work, not weeks of unknowns.

Where the risk actually is (all integration, none blocking):

1. **Android PDF text layer** — no built-in extraction; needs a lib decision (§12). iOS is clean.
2. **Range → on-page quad mapping** — the synthesizer speaks the *extracted* text; the highlight
   lands on the *rendered* PDF. The two coordinate systems must agree. De-hyphenation and multi-column
   reading order make this fiddly — the whole reason Phase 0 exists.
3. **Command reliability** — mitigated by push-to-talk + a constrained grammar in v1; open vocabulary
   waits for the semantic tier.
4. **Recognizer ordering** — cheapest if Recorder phase 1 lands first; otherwise Lector builds the
   push-to-talk subset (§4.3), which is not a fork.

Verdict: buildable to a real, shippable app in ~2 weeks of focused work once the `SpeechSynthesizer`
impl exists, with the reading loop de-risked in a 2–4 day spike first.

---

## 10. Open decisions

- **Android PDF library** — PdfBox-Android vs iText-style vs Pdfium-plus-shim, traded on text-box
  fidelity, license and size. Blocks the reading loop on Android; pick during Phase 0.
- **Recognizer: wait or build** — take Recorder phase 1's impl, or build the push-to-talk subset now.
  Depends on portfolio sequencing.
- **Highlight anchor** — char-offset-primary (chosen, §5) vs quad-primary; confirm once the Android
  extractor is chosen, since determinism depends on it.
- **Ambient overlay** — Android `AccessibilityService` Play-policy check before any phase-3 work;
  iOS excluded regardless.
- **On-device voice quality** — ship OS voices; revisit bundling a small neural voice (Kokoro/Piper
  via `reaktor-ffi`) only if OS quality tests poorly. Defer.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| Android text-layer extraction weak or heavy | lib bake-off in Phase 0; OCR fallback path already needed for scanned PDFs |
| Barge-in glitches (audio focus, double-speak) | explicit state machine + OS-interrupt as a first-class transition; the spike stops at read-only until it's solid |
| Highlights drift on reopen | char-offset anchor + deterministic extraction; quads as cache not source of truth |
| Voice commands misfire | push-to-talk + finite grammar in v1; semantic tier gated behind it |
| Scope creep toward a full Speechify | v1 is local PDF only; every richer source is an explicitly deferred phase |

---

## 12. Next

Phase 0 spike, against the `SpeechSynthesizer` stub in
`reaktor-media/src/commonMain/kotlin/dev/shibasis/reaktor/media/speech/`: pick the Android PDF lib,
render one page, wire one platform's synthesizer, prove word-highlight + auto-scroll off the range
callback. One platform first (Android or iOS), read-only, no voice-in — the moment that loop is
smooth, the rest is known work.
