# CLAUDE.md

Read [AGENTS.md](./AGENTS.md). It is the canonical agent guide for this repo.
The product-side guide at `../bestbuds/AGENTS.md` governs the design bar and build loops.

## Building on reaktor

Three skills carry what this framework costs people who learn it by crashing. Load them rather
than rediscovering the rules — every failure they describe happens at runtime, where the compiler
cannot help.

- **`reaktor-app`** — wiring an app: port keys by direction, never reading a port from `init`,
  adapter install order, routes being per-graph, the two platform-slot patterns.
- **`reaktor-module`** — where a capability belongs: reuse, extend, or a new module, and how to
  add one to this build. Read it before adding a platform capability here.
- **`reaktor-ship`** — getting a KMP app onto both stores: R8 and serialization, permissions that
  arrive through manifest merge, signing, asset sizes, listing limits.

When one of these turns out to be wrong or incomplete, fix the skill. A lesson that stays in a
single app's memory is one the next app pays for again.
