## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

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
