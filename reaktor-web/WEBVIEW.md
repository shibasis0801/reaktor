# reaktor-web — the webview

> **Stability: Design.** The module is currently a stub: three files, `commonMain` and `androidMain` have their class names swapped, and the only method is `fun t() { val x = 3 }`. Nothing in this document is implemented yet.

Reaktor apps need to show web content they did not author — a Grafana dashboard inside Reaktor Desktop, docs inside the app, an OAuth consent screen, a payment page, a map, a third-party embed. Today that happens ad hoc: `JcefGrafanaRenderer` in `targets/reaktorDesktop` wires JCEF through a Compose `SwingPanel` by hand, and there is no equivalent anywhere else.

This module owns that, on four platforms, behind one contract.

It is also [Reaktor Surface](https://reaktor.build/docs/reaktor-surface)'s answer to foreign views. Surface deliberately declines to model them — §4.1 gives them one sentence as a `PlatformSlot` escape hatch — because a foreign view is not a component: it has its own process, its own input handling and its own idea of what is on top. The escape hatch is the right shape. This module is what goes through it.

## The distinction that decides everything {#two-modes}

Every platform offers two fundamentally different things, and they are constantly confused:

**An embedded webview** is a surface your app fully controls. You can inject JavaScript, intercept navigation, read the DOM and observe every keystroke. It composes into your layout. It is the right tool for *your own* web content.

**An external browser** shows content in a surface your app cannot inspect or script, with the user's own session, cookies and password manager. It is the right tool for *someone else's* content — and it is the only correct tool for anything involving credentials.

> **The rule.** If the user would not want your app to be able to read what they are doing, it does not go in a webview your app controls. That isolation is not a limitation to work around; it is the feature. Apple enforces it, Google recommends it, and OAuth providers increasingly refuse embedded webviews outright.

This is why `WebTarget` below is a sealed type and not a boolean flag. Choosing wrong is a security decision, so the API makes you choose explicitly.

| | Embedded (you control it) | External (you do not) |
| --- | --- | --- |
| **Android** | `android.webkit.WebView` + `androidx.webkit` | Custom Tabs (`androidx.browser`), or an `ACTION_VIEW` intent |
| **iOS / macOS** | `WKWebView` | `SFSafariViewController`; `ASWebAuthenticationSession` for sign-in |
| **Web** | `<iframe sandbox>` | `window.open` / a plain link |
| **JVM desktop** | JCEF (`jcefmaven`) | `java.awt.Desktop.browse` |

## Dependencies and their maintenance {#dependencies}

Checked 13 September 2026. Reaktor does not adopt unmaintained libraries.

| Platform | Choice | Evidence |
| --- | --- | --- |
| Android embedded | `androidx.webkit` | **1.17.0 stable, 12 Aug 2026**; `1.18.0-alpha01`, 9 Sep 2026. First-party, actively released. |
| Android external | `androidx.browser` (Custom Tabs) | **1.10.0 stable, 25 Mar 2026**. First-party. |
| iOS / macOS | `WKWebView`, `SFSafariViewController`, `ASWebAuthenticationSession` | First-party Apple system frameworks. No third-party dependency, no version risk. |
| Web | `<iframe>`, `postMessage` | Platform. No dependency. |
| JVM desktop | `jcefmaven` | **146.0.10, 5 May 2026** (CEF 146 / Chromium 146.0.7680.179). Actively tracking Chromium. Already proven in this repository by `JcefGrafanaRenderer`. |

**Two libraries were considered and rejected.**

*KCEF* (`dev.datlag:kcef`) — **archived**, last release March 2025. It is the usual Kotlin-friendly wrapper over JCEF and it is no longer maintained.

*`compose-webview-multiplatform`* (KevinnZou) — the obvious off-the-shelf answer, and its desktop backend is KCEF. Adopting it would mean taking an archived Chromium embedder as a transitive dependency, on the surface where a stale Chromium is a security problem rather than a cosmetic one.

There is **no first-party Compose Multiplatform webview**; it is an open feature request (CMP-8105). So this module binds the platform APIs directly. That is more code than a wrapper, and it is code over four first-party, actively maintained surfaces instead of one unmaintained one.

## The contract {#contract}

`commonMain`. No Compose type appears in it — a webview is useful to a headless auth flow and to a server-side renderer's smoke test, not only to a UI.

```kotlin
package dev.shibasis.reaktor.web

/** Where the content should be shown. Sealed, because this is a security decision. */
sealed interface WebTarget {
    /** Your own content. Scriptable, interceptable, composes into your layout. */
    data class Embedded(val policy: WebPolicy) : WebTarget
    /** Someone else's content. The user's session and cookies; your app cannot read it. */
    data object External : WebTarget
    /** A sign-in flow. Isolated, returns exactly one callback URL, and nothing else. */
    data class Authentication(val callbackScheme: String) : WebTarget
}

data class WebRequest(
    val url: Url,
    val target: WebTarget,
    val headers: Map<String, String> = emptyMap(),   // embedded only; ignored elsewhere, never silently
)

/** What an embedded view is allowed to do. Defaults are the restrictive ones. */
data class WebPolicy(
    val allowedOrigins: OriginSet,             // navigation outside this set is refused, not followed
    val javaScript: Boolean = true,
    val storage: StorageMode = StorageMode.Ephemeral,   // Ephemeral | Persistent(id) | None
    val thirdPartyCookies: Boolean = false,
    val mixedContent: Boolean = false,         // never true in production; the terminal may refuse it
    val fileAccess: Boolean = false,
    val zoom: ZoomPolicy = ZoomPolicy.User,
    val mediaAutoplay: Boolean = false,
    val userAgent: UserAgent = UserAgent.Platform,
    val bridge: BridgeSpec? = null,            // null means no Kotlin is reachable from the page
)

interface WebSession {
    val state: StateFlow<WebState>             // Idle | Loading(progress) | Ready(title, url) | Failed(cause)
    val navigation: StateFlow<NavigationState> // canGoBack, canGoForward, currentUrl

    suspend fun load(request: WebRequest)
    suspend fun evaluate(script: String): JsonElement   // Embedded only; fails loudly on the others
    fun goBack(); fun goForward(); fun reload(); fun stop()

    /** Messages from the page. Typed, bounded and origin-tagged — never raw strings. */
    val messages: Flow<BridgeMessage>
    suspend fun post(message: BridgeMessage)

    suspend fun close()
}

interface WebHost {
    val capabilities: WebCapabilities
    suspend fun open(request: WebRequest): Result<WebSession>
}
```

`WebHost` is granted through Surface's `Capabilities`, which is what makes "this target has no embedded browser" a **declared answer rather than a crash** — see the matrix below.

## The bridge {#bridge}

All four platforms have the same shape underneath — a message channel between page and host — and four different spellings of it. The module normalises to one.

| Platform | Page → host | Host → page |
| --- | --- | --- |
| Android | `@JavascriptInterface` on an added object, or `WebViewCompat.postWebMessage` | `evaluateJavascript`, `postWebMessage` |
| iOS | `WKScriptMessageHandler` on `WKUserContentController` | `evaluateJavaScript` |
| Web | `postMessage` from the iframe | `iframe.contentWindow.postMessage` |
| JVM | a CEF message router query | `executeJavaScript` |

```kotlin
data class BridgeSpec(
    val name: String,                     // the single global the page sees
    val allowedOrigins: OriginSet,        // a message from anywhere else is dropped and reported
    val maxMessageBytes: Int = 64 * 1024,
    val schema: BridgeSchema,             // typed; an unknown message is a finding, not a silent no-op
)

data class BridgeMessage(val origin: Origin, val kind: MessageKind, val payload: JsonElement)
```

Four rules, and they are the whole security story of the bridge:

1. **Origin-checked on receipt.** Android's `@JavascriptInterface` and web's `postMessage` both deliver messages with no origin guarantee unless you check. The check is in the module, not in each caller.
2. **Typed and bounded.** A schema and a byte cap, because the page is untrusted input by definition.
3. **No bridge by default.** `BridgeSpec` is nullable and defaults to null. A webview with no bridge cannot reach Kotlin at all.
4. **Never on `External` or `Authentication`.** Those targets have no bridge and `evaluate` fails on them. That is the point of using them.

## Security {#security}

The obligations, stated once so no caller has to remember them.

**Navigation is allowlisted, not observed.** `WebPolicy.allowedOrigins` refuses a navigation rather than reporting it after the fact. A link to an unlisted origin either opens `External` or is refused — it never silently loads in a surface the user believes is the app.

**HTTPS only.** `mixedContent` defaults false; cleartext is a development-profile setting that the production build refuses. Certificate errors are **never** overridable by the caller — on Android that means never calling `proceed()` on `onReceivedSslError`, which is the single most common webview vulnerability shipped in production apps.

**Credentials never touch `Embedded`.** OAuth, SSO and payment flows use `Authentication` (`ASWebAuthenticationSession` / Custom Tabs) or `External`. This is also what providers require; Google has rejected embedded-webview OAuth for years.

**Storage is ephemeral by default.** `StorageMode.Persistent(id)` is opt-in and namespaced, so two embeds cannot read each other's cookies. Closing a session with `Ephemeral` storage clears it.

**Cleanup is guaranteed.** A webview is a process. `close()` must be called, and the Surface `PlatformSlot` disposal path calls it — a leaked JCEF browser is a leaked Chromium.

**Content is data.** Nothing a page sends over the bridge is an instruction. Page-supplied URLs are not opened, page-supplied scripts are not evaluated, and page text is never treated as a command by an agent reading the app.

## Surface integration {#surface}

The webview reaches the UI as a `PlatformSlot`, and the slot's declared obligations are exactly the ones a foreign view breaks if left unstated:

- **Target eligibility** — the slot declares which targets can host it; an ineligible target renders the declared fallback, not a blank box.
- **Input and focus ownership** — while the webview holds focus, the surrounding interaction machines see a `Cancel`, per §14.3 rule 3. There is no shared press state across the boundary.
- **Z-order** — the slot declares whether it can be occluded. JCEF's heavyweight mode and native iOS views **cannot** be reliably drawn under a Compose overlay, so an overlay that would cover a non-occludable slot is a finding at open time rather than a rendering bug at runtime. This is the single most important thing to state up front.
- **Disposal** — slot disposal closes the session. Always.

Everything above the slot stays with the caller: what URL, which target, what the fallback says, what the bridge means.

## Platform matrix {#matrix}

Honest about what each target can and cannot do. A missing capability is `Capabilities` returning a refusal, never a silent no-op.

| | Android | iOS / macOS | Web | JVM |
| --- | --- | --- | --- | --- |
| Embedded view | WebView | WKWebView | `<iframe sandbox>` | JCEF |
| External browser | Custom Tabs / intent | `SFSafariViewController` | `window.open` | `Desktop.browse` |
| Auth session | Custom Tabs + app links | `ASWebAuthenticationSession` | redirect | system browser + loopback |
| Bridge | yes | yes | yes, origin-checked | yes |
| Custom headers | yes | first request only | **no** — refused, not ignored | yes |
| Response interception | yes | limited | **no** | yes |
| Occludable by app UI | yes | **no** (native view) | **no** (iframe) | **no** in heavyweight mode |
| Cookie isolation | profile | `WKWebsiteDataStore` | third-party rules apply | request context |
| Runtime cost | system WebView | system WebKit | none | **~100 MB native download** |

**JCEF's footprint is the one that shapes the build.** The native bundle is downloaded and unpacked on first use, not shipped in the jar. It must be lazy — no Reaktor Desktop launch should pay for it unless a webview is actually opened — and the bootstrap has visible progress and an honest failure state. `JcefGrafanaRenderer` already does the download-with-progress dance; that logic moves here.

**Two of the four cannot be occluded.** On iOS and the web the webview is a real native view or a real iframe; nothing the app draws goes reliably on top. Designs that assume a floating panel over a webview must be checked on those targets, which is why z-order is declared rather than discovered.

## Build {#build}

Current targets in `build.gradle.kts` are `droid`, `darwin`, `web`, `server` — there is **no `jvm` target**, so Compose Desktop cannot consume this module today. Adding it is a prerequisite for the JCEF adapter. The `server` target has no webview and should expose the contract with a refusing `Capabilities`, so shared code compiles there without pretending.

## Sequence {#sequence}

| Step | Work | Exit evidence |
| --- | --- | --- |
| **W0** | Fix the stub: delete the swapped placeholder classes, add the `jvm` target, land the `commonMain` contract | `WebHost`/`WebSession` compile on all five targets; `server` returns a refusal |
| **W1** | Android and iOS embedded + external, no bridge | The same URL loads embedded and externally on both; an off-allowlist navigation is refused; a certificate error is not overridable |
| **W2** | JVM via JCEF, lazy bootstrap | Reaktor Desktop's Grafana pane runs on this module; `JcefGrafanaRenderer` is deleted; app launch does not download Chromium |
| **W3** | Web via sandboxed iframe | Same contract; unsupported capabilities (headers, interception) refuse rather than no-op |
| **W4** | The bridge | Typed round-trip on all four; a wrong-origin message is dropped and reported; an over-size message is rejected |
| **W5** | `Authentication` | A real OAuth flow through `ASWebAuthenticationSession` and Custom Tabs; no credential ever reaches an `Embedded` view |
| **W6** | Surface `PlatformSlot` | Focus transfer emits `Cancel`; an overlay over a non-occludable slot is a finding; slot disposal closes the session with no leaked process |

## Related

- [Reaktor Surface](https://reaktor.build/docs/reaktor-surface) — §4.1 `PlatformSlot`, §14.7 foreign content, §18 the additions this module sits beside.
- [Surface Migration Plan](https://reaktor.build/docs/reaktor-surface-migration-plan) — gap G4, which this module answers.
