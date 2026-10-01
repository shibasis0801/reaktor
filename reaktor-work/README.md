# reaktor-work

**Status: Experimental. Source reviewed 26 September 2026.**

This module currently wraps Meeseeks 1.0.2 with `TaskManager`, `Feature.Work` and thin Android, iOS, JVM and browser JS context adapters. It forwards scheduling, cancellation, rescheduling and status observation. The ten bundled workers are log-only placeholder bodies; they do not implement sync, upload, token refresh or the other effects their names suggest.

Native persistence and execution must be qualified with application-level bootstrap. The pinned JVM dependency has a documented initialization defect. Browser JS is not Node.js support, and there is no dedicated Hermes or Cloudflare Work executor.

The [public Work status page](https://reaktor.build/docs/reaktor-work) and the authenticated [Durable Execution Roadmap](https://reaktor.build/docs/private/reaktor-work-roadmap) replace the previous guide. The roadmap owns the proposed graph contract, durable intent and effect protocol, platform qualification, dependency migration, delivery milestones and acceptance gates. Those proposed APIs are not implemented by this README change.
