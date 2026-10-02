# reaktor-google

> **Stability: Experimental** - Pub/Sub is integrated and functional, and user-consent connectors for Calendar, Drive and YouTube run on the JVM.

`reaktor-google` provides cross-platform abstractions for Google Cloud services (Pub/Sub) and, on the JVM, connectors that act for a person on Google APIs after they consent.

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

## User-consent connectors (JVM)

`dev.shibasis.reaktor.google.connect` lets a server act for a person on Google APIs after that person consents. A service calls it with a `GrantKey(service, subject)`; grants never cross keys.

| Type | Purpose |
|---|---|
| `GoogleConnector` | `begin` builds the consent address (authorization code, PKCE S256, offline access, incremental scopes, `login_hint`); `complete` takes the callback's state and code once; `grant`, `revoke` and `call` work on a stored grant |
| `GoogleGrantStore` | Where sealed grants and pending consents live. `PostgresGoogleGrantStore` keeps them in the `google_connect` schema, with row-level security on and every privilege revoked from roles other than the owner |
| `GrantSealer` | AES-256-GCM under a key derived with HKDF-SHA-256 for each service, subject and purpose, from a secret of at least 32 characters |
| `GoogleCall` | The result of an operation: Google's JSON, or why it did not run |

- A pending consent lives ten minutes, is used once and is stored only as the hash of its state, with the PKCE verifier and the return address sealed.
- The account's email comes from the id token returned by the token endpoint, after its audience and issuer are checked.
- Access tokens are refreshed a minute before they expire, once per grant at a time; a rotated refresh token replaces the old one, and `invalid_grant` forgets the grant.
- `call` runs only allow-listed operations on the official Google API clients, and only when the grant holds one of the scopes the operation needs:
  - Calendar: `calendars.insert`, `calendars.get`, `events.insert`, `events.patch`, `events.delete`, `events.list` (sync tokens), `events.watch`, `channels.stop`, `freebusy.query`; Meet links come from `conferenceData` on insert and patch.
  - Drive: `files.create` (with optional media, so HTML converts into a Doc), `files.get`, `files.export` as text.
  - YouTube: `playlists.list`, `channelSections.list`, `playlistItems.list`.

## Dependencies

- `reaktor-auth` (shared)
- `reaktor-crypto` (JVM server only)
- Google Cloud Pub/Sub SDK (JVM server only)
- Google Sheets, Calendar, Drive and YouTube Data API clients, and `google-auth-library` (JVM server only)

## Intended use

Use this module when a product needs:
- Pub/Sub publishing from shared code
- A single abstraction across server and client runtimes
- Service endpoints backed by Pub/Sub topics
