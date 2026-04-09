# reaktor-location

> **Stability: Early** - Intentionally minimal adapter surface, not a full mapping SDK.

`reaktor-location` provides cross-platform location access through shared adapters.

## Platforms

Android (Google Play Services), iOS/Darwin (CoreLocation)

## Key types

| Type | Purpose |
|---|---|
| `Location` | Data class with `longitude`, `latitude` (serializable) |
| `LocationAdapter<Controller>` | Abstract base with `getLocation()` suspend function |
| `MapAdapter` | Map interface (placeholder) |
| `AndroidLocationAdapter` | Android implementation via Google Play Services Location |
| `DarwinLocationAdapter` | iOS implementation via CoreLocation framework |

## Goal

Shared code should be able to request location without knowing whether the host is Android or iOS.

## Dependencies

- `reaktor-ui`
- Google Play Services Location 21.2.0 (Android)
- CoreLocation framework (iOS/Darwin)
