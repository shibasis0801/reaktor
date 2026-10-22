# Semantic subgraphs

Local Claude/Codex interprets intent and proposes canonical graph members through
MCP. The application retrieves and validates real nodes and relationships; it does
not call a hosted model. Blueprint supplies the shared editor and preview.

## Workflow

1. Discover relevant entities and retrieve candidates, using exact seed ids when
   the local agent has identified the concepts or runtime components.
2. Write a selection with `status: "draft"` and a reason for each semantic choice.
3. Open it in the host, add/remove candidate nodes and individual relationships,
   then choose **Save subgraph**. The agent can also revise it through MCP.
4. Reopen the saved selection in the host. Membership references the original
   graph; it does not copy or modify source nodes.

Manna exposes **Subgraphs** beside Atlas. Hangar exposes
**Graph → Whole system → Subgraphs**.

## Manna MCP

- `search_workspace` with mode `related` discovers semantic seed concepts.
- `query_subgraph` accepts `query`, `seeds`, `depth` (0–3), `direction`
  (`both`, `upstream`, `downstream`), relationship `kinds`, and `limit` (1–500).
- `put_subgraph` accepts `selection`, `expected_rev`, `request_id` and optional
  `space`. Use `expected_rev: 0` for new ids. Read `list_subgraphs` before edits;
  its `rev` is the storage revision, while `selection.revision` is the document
  revision and increments by one. Reuse `request_id` for retries.
- `list_subgraphs` returns exact draft/saved selections, filtered by access.

Canonical ids are `source:<resource-id>` and
`unit:<resource-id>:<zero-based-chapter-index>`. Only public catalogue books and
courses are searchable. Relationships returned by the query include containment,
resource prerequisites/optional/related links and chapter links. Preserve their
returned ids. The API validates references again for direct writes.

## Hangar MCP

Through the Reaktor kernel tool, call operations `subgraphs.query`,
`subgraphs.list`, and `subgraphs.put`. Query uses the same arguments as above.
Put accepts `{selection, expectedRevision}`; new ids use expected revision zero.
The selection's revision must be the expected revision plus one.

Queries read the currently selected application's Whole system graph and open
the Graph pane. A headless local kernel reads it through the running Hangar
desktop MCP bridge, which checks the canonical workspace root. Source graph
identity includes workspace, environment and application. Views persist in
`.reaktor/subgraphs.json`; an open Hangar observes writes from another process.

## Selection format

`SubgraphResult.proposal(id, title, intent)` creates a selection in Kotlin. MCP
clients form the same JSON from a query response:

```json
{
  "id": "sg_my_view",
  "title": "Parkinson twin scoring foundations",
  "intent": "Neuroscience and neuroanatomy for structural and dopaminergic scoring",
  "graphId": "<graphId from query>",
  "sourceRevision": "<sourceRevision from query>",
  "nodes": ["<canonical node id>"],
  "edges": ["<canonical relationship id>"],
  "reasons": {"<canonical node id>": "Why the local agent selected this node"},
  "status": "draft",
  "revision": 1
}
```

The editor shows source changes and unavailable members. Saving rejects missing
references, foreign graphs and stale document revisions. Explicit relationship
exclusions survive subsequent node edits and reopen. Query text is a lexical
candidate retriever; semantic interpretation belongs to the local agent, with
Manna's existing related search available for discovery. Expansion follows actual
graph links and reports truncation at the requested bound.
