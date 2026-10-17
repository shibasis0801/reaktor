-- Optional host-owned storage; NOT an automatic application migration.
-- Provision with an admin account. The search adapter needs SELECT only.
-- Ingestion/retention and the current revision/ACL resolver belong to the host.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS reaktor_context_chunks (
    tenant_id text NOT NULL,
    workspace_id text NOT NULL,
    source_id text NOT NULL,
    source_revision text NOT NULL,
    chunk_id text NOT NULL,
    embedding_model text NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions BETWEEN 1 AND 4096),
    embedding vector NOT NULL,
    indexed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, workspace_id, source_id, source_revision, chunk_id, embedding_model, dimensions),
    CHECK (vector_dims(embedding) = dimensions),
    CHECK (vector_norm(embedding) > 0)
);

-- Exact filtered retrieval first; choose model-specific ANN indexes only after recall benchmarks.
CREATE INDEX IF NOT EXISTS reaktor_context_scope_model
    ON reaktor_context_chunks (tenant_id, workspace_id, embedding_model, dimensions);
