# reaktor-devtools

The agent that ships inside every Reaktor app, and the workbench's end of the link.

## Why it exists

Everything a host tool can learn about a running app from outside it — `adb`, `idb`, `simctl`,
a proxy — stops at bytes. It can see that a socket was written to; it cannot say which port
issued the call, which actor was waiting, or what contract the call declared. Certificate
pinning hides the traffic entirely, and Apple gives no shell to go looking.

An agent inside the process answers those questions directly, and it is the only thing that can.

## What it is made of

Nothing new that the app was not already carrying:

| Concern | Comes from |
| --- | --- |
| Protocol | `reaktor-service` — one `Service` declaration compiled into both ends |
| Streaming | newline-framed JSON over the platform's own socket |
| Element tree | Compose, so Android and iOS share one implementation |
| Port events | `reaktor-graph-port`'s K1 `PortInterceptor` and K2 attachments |
| Traffic | a `ServiceInterceptor`, above TLS, with a correlation id |

## Reaching it

The agent listens; the workbench dials. That is the direction the platform forwarding primitives
already run in, so no discovery and no IP address is involved.

| Runtime | Binds | Reached by |
| --- | --- | --- |
| Android | `localabstract:reaktor-devtools` | `adb forward tcp:<port> localabstract:reaktor-devtools` |
| Apple simulator | `127.0.0.1:47821` | directly — the simulator shares the host's loopback |
| Apple device | `127.0.0.1:47821` | `idb forward` over usbmux |
| Desktop | `127.0.0.1:47821` | directly |
| Browser | nothing | a page cannot listen; a web agent dials out instead |

## Installing it in an app

```kotlin
val agent = DevToolsAgent(
    applicationId = "ai.bestbuds.app",
    displayName = "BestBuds",
    revision = AgentRevision(artifact, configuration, activation, graphDigest),
    policy = AgentPolicy(writable = true),
)
agent.register(OverrideStore().handler())
agent.start()
DevToolsHost(agent, devToolsTransport()).start()
```

Then wrap the Compose root so tagged elements register:

```kotlin
ReaktorDevTools(agent) { App() }
```

and tag what should be inspectable:

```kotlin
Modifier.reaktorElement(id = "chat.send", graphNodeId = chatNode.id, clickable = true)
```

Gate the whole thing on a build flag. BestBuds uses `bestbuds.devTools`, which compiles to a
constant, so a release build contains no listening socket at all.

## Attaching from the workbench

```kotlin
val (attachment, descriptor) = attachThroughForward(scope) { port ->
    session.forward(port, RemoteSocket.LocalAbstract(DevToolsProtocol.AndroidSocketName))
}
attachment.subscribe(AgentCapability.Logs)
```

## What it reports

Capabilities are derived from what is actually wired, never declared, and an unavailable one
carries the reason rather than disappearing:

```json
{ "name": "semantics", "fidelity": "Static",
  "unavailableReason": "No semantics provider is installed" }
```

Facts carry their own sequence and monotonic clock reading, because phone wall clocks jump and a
timeline built on them shows spans travelling backwards. A fact taken while something held the app
still is stamped `perturbed`, and no duration may be derived from a perturbed interval.

## What it will not do

- **Block the app.** Every buffer drops rather than waits, and reports what it dropped.
- **Capture payloads** until the redaction contract exists. Descriptors are not payloads.
- **Accept writes in a release build.** The check lives in `DevToolsAgent.execute`, once, rather
  than in each handler.
