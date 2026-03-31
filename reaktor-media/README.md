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
