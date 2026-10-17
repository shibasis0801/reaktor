# Reaktor WebView runtime

Graph-owned web sessions, local resource bundles and a typed JavaScript bridge. Priority targets are macOS, Android, iOS and browser iframes. Desktop uses [webview/webview](https://github.com/webview/webview) through Reaktor's C++ ABI 2 and JNI; `reaktor-ffi` can reuse the boundary later. The [runtime plan and diagrams](https://reaktor.build/docs/reaktor-webview) document platform limits and rollout gates.

`reaktor-web` depends on `reaktor-graph-runtime` and contains no Compose dependency. The `ReaktorWebView` adapter lives in the existing `reaktor-ui` module.

```kotlin
val runtime = graph.Node { WebRuntime(it) }
val bridge = graph.Node {
    WebBridge(it, documentService, grants, authorize = ::authorizeDocumentOperation)
}
val app = WebApp("document", "v1", documentAssets + WebBridgeAssets,
    requiredFeatures = setOf(WebFeature.Bundles, WebFeature.Bridge))

ReaktorWebView(runtime, WebContent.Bundle(app), state = rememberWebViewState(), bridge = bridge)
```

Use the graph factory to attach nodes. `WebRuntime.open(host, options, bridge)` creates a session without UI dependencies. Sessions expose immutable navigation state, evaluation results, focus, history, zoom, reload and download notifications where supported. `features`, `requireFeature` and `WebApp.requiredFeatures` produce explicit `WebUnavailable` results for missing capabilities. Graph closure, detach and view disposal release sessions and channels.

Native bundles load directly from resource manifests: Android intercepts a private HTTPS app origin; Apple uses a WK custom scheme. Assets carry MIME/CSP and are bounded to 8 MiB each and 64 MiB per bundle. Paths reject traversal and ambiguous escaping. `WebAssets` owns copied bytes. Custom schemes do not promise HTTPS secure-context or worker parity.

Browser bundles use `WebApp.browserUrl` on an HTTPS origin dedicated to the app/revision, separate from the parent. Deploy the entrypoint, assets and `WebBridgeAssets` there with the app CSP and appropriate `frame-ancestors`. Choose `WebViewOptions(profile = WebProfile.Browser)`. The iframe's exact origin, frame, fresh token and private MessagePort establish the channel. Opaque remote/HTML frames get no bridge. Same-origin privileged bundles are rejected; browser storage/cookies follow browser partitioning rather than native profiles. The opaque-sandbox service-worker experiment was removed because browsers do not control those frames with a service worker.

The TypeScript SDK supports `window.reaktor.invoke(operation, payload, {signal, traceId})` and `subscribe(operation, listener, signal)`. It uses existing typed `Service` operation/schema descriptors. Hosts provide `WebGrant` and a mandatory authorization callback; JavaScript cannot supply its principal, workspace or environment. Revocation/navigation cancels pending work and subscriptions. Transport limits are 32 pending operations, 64 KiB messages, JSON depth 64 and 15-second deadlines; mutations are never replayed after renderer recovery.

Media, geolocation, popup and file chooser access defaults to denial. Native clipboard/files/shell/deploy permissions must pass through approved host services. External developer tools inspect debug WebViews where a public in-app inspector API is unavailable.

Desktop native commands run on the platform thread. macOS uses an unshown staging window and reparents upstream's NSView container into the matched AWT window's content view; the separate browser-controller handle supplies WKWebView. The parent remains retained until queued native teardown completes. Commands are queued before AppKit dispatch so the EDT never waits on AppKit. Standard macOS editing actions route to the focused WKWebView; Select All and Unicode paste pass in the Compose fixture using Hangar's packaged JVM and production libraries with debugging disabled. Removing and reattaching the AWT panel creates a fresh session. URL changes reuse the existing host.

Compose adapters manage measured bounds, visibility, host replacement and disposal. Native views require an explicit overlap strategy: hide/detach them while a Compose modal covers their area. Arbitrary clipping and overlay parity remain platform acceptance concerns.

Build on M1:

```sh
~/dev/tools/remote-dev build reaktor \
  :reaktor-web:jvmTest :reaktor-web:testWebBridge \
  :reaktor-web:assembleDebugAndroidTest \
  :reaktor-ui:compileKotlinJvm :reaktor-ui:compileKotlinJs \
  :reaktor-ui:compileDebugKotlinAndroid :reaktor-ui:compileKotlinIosArm64
```

Real-window fixtures are `DesktopWebViewProbeKt` and `ComposeWebViewProbeKt`. Their classpaths are exported by `jvmDesktopWebViewProbeClasspath` and `:reaktor-ui:jvmComposeWebViewProbeClasspath`. Translate worker checkout paths to local copies and launch compiled classes on the UI Mac with Java 25 and `--enable-native-access=ALL-UNNAMED`. Do not launch their windows on the worker.

The Android instrumentation fixture runs on a connected phone. The opt-in iOS test framework uses `-Preaktor.web.deviceProbe=true :reaktor-web:linkWebViewProbeDebugFrameworkIosArm64`; its Swift device host is in `native/device-probe`, and its Kotlin entrypoint is in `iosTest`. It does not replace a product app. `BrowserWebViewProbe.kt` exports the separate-origin iframe fixture for local acceptance.

Verified on 6 October 2026: 12 JVM contract/lifecycle tests and five TypeScript tests pass. Physical Android and iPhone probes pass bundled modules, typed Unicode replies and evaluation. Priority target hosts/Compose adapters compile, and Hangar's system-WebView app image/DMG builds on M1. macOS bundle/bridge/evaluation and Compose two-pane placement, focus/input, resizing, zoom and visibility/modal checks pass. The separate-origin browser bridge and Unicode input probe pass. Packaged Hangar renders Grafana’s loading page; IME/VoiceOver/multiple-monitor and authenticated dashboard acceptance remain rollout gates in the plan. Linux C++ compiles; Windows compilation and Windows/Linux window checks are deferred. Tool-specific Tiptap/Monaco/xterm assets belong to consuming modules and require their own workload checks.
