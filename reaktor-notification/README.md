# reaktor-notification

> **Stability: Brainstorming** - Intentionally thin. Exists as a shared seam for future platform implementations.

`reaktor-notification` is the shared notification adapter surface.

## Current purpose

- Provide a stable abstraction for notification delivery and registration
- Keep notification integration out of product code where possible

## Key types

| Type | Purpose |
|---|---|
| `NotificationAdapter<Controller>` | Abstract base extending the core `Adapter` framework |

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web (all via build configuration, implementation pending)

## Status

This module is a placeholder. It defines the adapter surface so that platform-specific notification implementations (FCM, APNs, etc.) can be wired in later without changing product code.
