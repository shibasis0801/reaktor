# reaktor-google

> **Stability: Experimental** - Pub/Sub is integrated and functional. Other Google services may be added.

`reaktor-google` provides cross-platform abstractions for Google Cloud services, currently focused on Pub/Sub.

## What it provides

- Shared Pub/Sub data types
- Adapter abstraction for publish / subscribe / pull / ack flows
- Platform-specific adapters for JVM, Android, Darwin, and Web
- A slot in `Feature.GooglePubSub` for runtime installation

## Platforms

| Platform | Status |
|---|---|
| JVM | Fully functional via Google Cloud SDK |
| Web/JS | Fully functional via REST API |
| Android | Stub (designed for JVM server fallback) |
| iOS/Darwin | Stub |

## Key types

| Type | Purpose |
|---|---|
| `PubSubTopic` | Topic identifier (projectId + topicId) |
| `PubSubSubscription` | Subscription identifier |
| `PubSubMessage` | Message to publish (data + attributes + ordering key) |
| `PulledPubSubMessage` | Message received from subscription |
| `PubSubAdapter<Controller>` | Abstract platform adapter |

## Operations

- `ensureTopic()` - Create topic (idempotent)
- `ensureSubscription()` - Create subscription (idempotent)
- `publish()` - Publish messages with attributes and ordering keys
- `pull()` - Fetch messages with max count
- `acknowledge()` - Acknowledge pulled messages

All operations return `Result` for error handling without exceptions.

## Dependencies

- `reaktor-auth` (shared)
- Google Cloud Pub/Sub SDK (JVM server only)
- Google Sheets API (JVM server only)

## Intended use

Use this module when a product needs:
- Pub/Sub publishing from shared code
- A single abstraction across server and client runtimes
- Service endpoints backed by Pub/Sub topics
