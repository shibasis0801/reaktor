# reaktor-media

> **Stability: Early** - Camera and gallery work on Android/iOS. Speech adapters are placeholders.

`reaktor-media` is the media capability layer for Reaktor apps.

## Platforms

Android (primary), iOS/Darwin (primary), JVM/JS (minimal)

## Current areas

### Camera

| Type | Purpose |
|---|---|
| `CameraAdapter<Controller>` | Abstract base with lifecycle (start, switchCamera), render, file/analyzer |
| `CameraComponent` | Android camera implementation |
| `DarwinCameraAdapter` | iOS camera implementation |
| `CameraScreen` | Composable camera UI |
| `ReaktorCamera` | Android camera wrapper |
| `CameraStart` | Enum: Success, ControllerFailure, PermissionFailure, CameraFailure |

### Gallery

| Type | Purpose |
|---|---|
| `DarwinGalleryAdapter` | iOS gallery/media selection |

### Image loading

| Type | Purpose |
|---|---|
| `AsyncImage` | Image loading composable |
| `Cache` / `FileBasedCache` | Image caching |
| `CoilSetup` | Coil image library integration |

### Speech (placeholders)

| Type | Purpose |
|---|---|
| `SpeechRecognizer<Controller>` | Placeholder abstract class |
| `SpeechSynthesizer<Controller>` | Placeholder abstract class |

### PDF reading

`PdfReader(path, state, modifier)` embeds a native reader for an app-private local file.
`PdfReaderState` exposes page/zoom changes, search results and text selections with
zero-based page indices and top-left page bounds. It accepts page, zoom and search
commands and releases native resources when its composable leaves the screen.

- Android API 28+: AndroidX PDF View and sandboxed document service. Search and
  selection depend on the document service's available text features.
- Android API 26–27: `PdfRenderer` pages with navigation, pan and zoom.
- iOS: PDFKit with native selection, search and zoom.
- JVM/JS: unsupported capability reported through reader state.

Apps own authenticated downloads, private file staging, persisted positions,
bookmarks and actions that turn a passage into domain content. Manna implements
those in its reader feature; they are not global Reaktor services.

## What this module is not

It is not a full backend media pipeline. Upload authorization, storage, and background work are usually composed with:
- `reaktor-auth`
- `reaktor-db`
- `reaktor-work`
- Product-specific media services

## Dependencies

- `reaktor-graph`, `reaktor-io`
- Coil 3.2.0 (image loading, network, SVG)
- Android Camera, WorkManager (Android only)
