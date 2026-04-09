# reaktor-work

> **Stability: Experimental** - Functional with platform-native schedulers. API may evolve.

`reaktor-work` is Reaktor's background task orchestration layer. Products define work in shared Kotlin code; the platform's task manager (Android WorkManager, iOS BGTaskScheduler, JVM scheduler, or Node.js) handles actual scheduling and execution.

## Platforms

Android, iOS (Darwin), JVM, JavaScript/Web

## Core types

| Type | Purpose |
|---|---|
| `TaskManager<Controller>` | Abstract base; delegates to platform manager |
| `AndroidTaskManager` | Android WorkManager implementation |
| `DarwinTaskManager` | iOS BGTaskScheduler implementation |
| `JvmTaskManager` | JVM/server implementation |
| `JsTaskManager` | JavaScript/Node.js implementation |
| `Worker<TPayload>` | Base class for reusable task implementations |

## Built-in worker shapes

| Worker | Purpose |
|---|---|
| `SyncWorker` | Periodic data synchronization |
| `TokenRefreshWorker` | OAuth token refresh before expiry |
| `AnalyticsUploadWorker` | Batch upload of analytics events |
| `MediaUploadWorker` | Upload pending media files |
| `DatabaseMaintenanceWorker` | Database optimization (vacuum, compact) |
| `CacheCleanupWorker` | Remove expired cache entries |
| `NotificationSyncWorker` | Sync notification state |
| `HeartbeatWorker` | Periodic keep-alive |
| `PrefetchWorker` | Preload content for offline use |
| `LogUploadWorker` | Collect and upload logs |

## Task capabilities

- One-time and periodic scheduling
- Initial delay and flex windows
- Backoff configuration (min: 15s, max retries: 3)
- Max parallel tasks (4 concurrent by default)
- Task status monitoring and cancellation
- Observable task flows

## Dependencies

- `reaktor-core`
- Meeseeks runtime (unified task scheduling framework)
- Koin dependency injection (JVM)

## Goal

Products should be able to schedule background work from shared code while leaving the host runtime details to the platform task manager.
