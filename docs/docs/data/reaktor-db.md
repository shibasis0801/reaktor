# Reaktor-DB: Global Data Architecture

Reaktor-DB is the unified data layer that orchestrates six storage primitives into a coherent, latency-tiered architecture. It handles everything from sub-millisecond local reads to petabyte-scale archival storage.

## Design Principles

-   **Locality-First Reads**: Data is served from the closest and cheapest tier that has it.
-   **Write Where Truth Lives**: Every data domain has exactly one authoritative store. Caches are derived, never authoritative.
-   **Event-Driven Propagation**: State changes flow through Pub/Sub to invalidate or update caches.
-   **Cost-Proportional Storage**: Hot data stays in fast, expensive tiers; cold data sinks to R2 archival storage automatically.
-   **India-First, Global-Ready**: Deployment topology and compliance decisions prioritize Indian users as the primary region.

## The Five-Tier Data Model

| Tier | Store | Latency | Durability |
| --- | --- | --- | --- |
| **T0: Local Device** | SQLite (SQLDelight, wa-sqlite) | &lt;1ms | Device-local, ephemeral cache. |
| **T1: Edge** | Cloudflare Durable Objects + SQLite | 1-10ms (same colo) | Durable per-object, single-writer. |
| **T2: Regional Transactional** | Supabase Postgres (Mumbai/London/US) | 10-50ms (same region) | Full ACID, WAL-replicated. |
| **T3: Regional Graph** | Memgraph on GCP Compute Engine | 5-30ms (in-memory) | In-memory with snapshot persistence. |
| **T4: Archival** | Cloudflare R2 | 50-150ms (first byte) | 11-nines durability, zero egress. |

## Conflict Resolution Strategy

Reaktor-DB implements CRDT-based merge for key data types to ensure consistent state across offline devices:

-   **LWW-Register (Last-Writer-Wins)**: For scalar fields like display names or bio preferences. Uses a Hybrid Logical Clock (HLC).
-   **G-Counter / PN-Counter**: For counters like view counts or inventory levels.
-   **OR-Set (Observed-Remove Set)**: For set-valued fields like tags or community memberships.
-   **RGA (Replicated Growable Array)**: For ordered sequences like message composition or collaborative text editing.

## Blueprint Integration

The Reaktor-DB tier topology is visible in the Blueprint editor as a data flow overlay. Developers can trace a single data entity's journey from write, through its various cache populations, and eventually to archival storage.
