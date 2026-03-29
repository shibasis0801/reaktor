# reaktor-work

`reaktor-work` is Reaktor's background task orchestration layer.

## Core types

- `TaskManager`
- `Worker<TPayload>`
- platform task manager implementations for Android, iOS, JVM, and JS

## Built-in worker shapes

The module currently ships reusable worker types such as:
- sync
- token refresh
- analytics upload
- media upload
- database maintenance
- cache cleanup
- notification sync
- heartbeat
- prefetch
- log upload

## Goal

Products should be able to schedule background work from shared code while leaving the host runtime details to the platform task manager.
