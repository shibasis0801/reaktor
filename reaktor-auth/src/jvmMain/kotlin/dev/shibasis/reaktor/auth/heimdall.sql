-- heimdall.sql
-- ---------------------------------------------------------------------------
-- Authoritative phase-2 reaktor-auth schema.
--
-- This file is intentionally not a legacy migration. Reaktor auth now models:
-- app -> identity/provider_account -> principal -> membership -> role/permission
-- plus sessions, refresh tokens, PATs, service accounts, and signing keys.
--
-- To rebuild Supabase, dump data separately, create an empty database/schema,
-- run this file, then restore compatible heimdall/public data.
-- ---------------------------------------------------------------------------

\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS heimdall;
SET search_path TO heimdall, public;

GRANT ALL ON SCHEMA heimdall TO public;
GRANT USAGE ON SCHEMA heimdall TO anon;
ALTER DEFAULT PRIVILEGES IN SCHEMA heimdall GRANT SELECT ON TABLES TO anon;

CREATE TABLE IF NOT EXISTS auditable (
    data jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE OR REPLACE FUNCTION on_update()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS app (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE app IS 'Product/application namespace. Roles, permissions, contexts, tenants, and credentials are app-scoped.';

CREATE TABLE IF NOT EXISTS identity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    status varchar(50) DEFAULT 'ACTIVE' NOT NULL,
    primary_email text NULL
) INHERITS (auditable);
COMMENT ON TABLE identity IS 'Global human identity. A person can have many provider accounts and many app memberships.';

CREATE TABLE IF NOT EXISTS provider_account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id uuid NOT NULL REFERENCES identity(id) ON DELETE CASCADE,
    provider varchar(20) NOT NULL,
    issuer text NOT NULL,
    subject text NOT NULL,
    email text NULL,
    email_verified boolean DEFAULT false NOT NULL,
    provider_tenant text NULL
) INHERITS (auditable);
COMMENT ON TABLE provider_account IS 'External login method attached to an identity: Google, Apple, Supabase, or Reaktor.';

CREATE TABLE IF NOT EXISTS principal (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind varchar(20) NOT NULL,
    identity_id uuid NULL REFERENCES identity(id) ON DELETE SET NULL,
    status varchar(50) DEFAULT 'ACTIVE' NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE principal IS 'Authorizable caller: USER, SERVICE, AGENT, or ACTOR.';

CREATE TABLE IF NOT EXISTS context (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);
COMMENT ON TABLE context IS 'App-local authorization context for resource- or workflow-scoped grants.';

CREATE TABLE IF NOT EXISTS tenant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL,
    status varchar(50) DEFAULT 'ACTIVE' NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE tenant IS 'Organizational unit inside an app. Tenant isolation is enforced through AuthContext and resource checks.';

CREATE TABLE IF NOT EXISTS membership (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id uuid NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    tenant_id uuid NULL REFERENCES tenant(id) ON DELETE SET NULL,
    context_id uuid NULL REFERENCES context(id) ON DELETE SET NULL,
    status varchar(50) DEFAULT 'ACTIVE' NOT NULL,
    profile jsonb DEFAULT '{}'::jsonb NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE membership IS 'Principal membership in an app/tenant/context.';

CREATE TABLE IF NOT EXISTS role (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(50) NOT NULL,
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE IF NOT EXISTS permission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE IF NOT EXISTS role_permissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id uuid NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permission(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE IF NOT EXISTS principal_role (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id uuid NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    context_id uuid NULL REFERENCES context(id) ON DELETE CASCADE
) INHERITS (auditable);
COMMENT ON TABLE principal_role IS 'Role grant for a principal, optionally context-scoped.';

CREATE TABLE IF NOT EXISTS auth_provider_client (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    provider varchar(20) NOT NULL,
    platform varchar(20) NOT NULL,
    client_id text NOT NULL,
    client_secret_ref text NULL,
    issuer text NULL,
    apple_team_id text NULL,
    bundle_id text NULL,
    redirect_uris jsonb DEFAULT '[]'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE auth_provider_client IS 'Per-app/per-platform OAuth client config. Secrets are references, not secret values.';

CREATE TABLE IF NOT EXISTS session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id uuid NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    tenant_id uuid NULL REFERENCES tenant(id) ON DELETE SET NULL,
    context_id uuid NULL REFERENCES context(id) ON DELETE SET NULL,
    expires_at timestamptz NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE session IS 'Principal login session. Refresh-token families are anchored here.';

CREATE TABLE IF NOT EXISTS refresh_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    token_hash varchar(255) UNIQUE NOT NULL,
    family_id uuid NOT NULL,
    principal_id uuid NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    previous_token_id uuid NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz NULL,
    rotated_at timestamptz NULL,
    revoked_at timestamptz NULL
) INHERITS (auditable);
COMMENT ON TABLE refresh_token IS 'One-time-use rotating refresh tokens. Reuse of a used token revokes the family.';

CREATE TABLE IF NOT EXISTS service_account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id uuid NOT NULL REFERENCES app(id) ON DELETE CASCADE,
    principal_id uuid NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    client_id varchar(255) UNIQUE NOT NULL,
    name varchar(100) NOT NULL,
    auth_method varchar(30) DEFAULT 'PRIVATE_KEY_JWT' NOT NULL,
    public_jwks text NULL,
    secret_hash varchar(255) NULL,
    scopes text DEFAULT '' NOT NULL,
    allowed_audiences text DEFAULT '[]' NOT NULL,
    status varchar(50) DEFAULT 'ACTIVE' NOT NULL
) INHERITS (auditable);
COMMENT ON TABLE service_account IS 'Machine credential for client_credentials and token-exchange actor authentication.';

CREATE TABLE IF NOT EXISTS personal_access_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id uuid NULL REFERENCES principal(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL,
    token_hash varchar(255) UNIQUE NOT NULL,
    scopes text NOT NULL,
    context_id uuid NULL REFERENCES context(id) ON DELETE CASCADE,
    expires_at timestamptz NULL,
    last_used_at timestamptz NULL,
    revoked_at timestamptz NULL,
    app_id uuid NULL REFERENCES app(id) ON DELETE SET NULL,
    allowed_audiences text NULL
) INHERITS (auditable);
COMMENT ON TABLE personal_access_token IS 'Long-lived secret handle. Raw token is shown once, SHA-256 hash is stored, exchange mints short-lived audience-bound JWTs.';

CREATE TABLE IF NOT EXISTS signing_key (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kid varchar(64) UNIQUE NOT NULL,
    alg varchar(16) DEFAULT 'ES256' NOT NULL,
    public_jwk jsonb NOT NULL,
    private_key_ref text NULL,
    status varchar(16) DEFAULT 'current' NOT NULL,
    rotated_at timestamptz NULL,
    expires_at timestamptz NULL
) INHERITS (auditable);
COMMENT ON TABLE signing_key IS 'Public JWT signing-key metadata. Private keys live in Secret Manager and are referenced only by private_key_ref.';

CREATE TABLE IF NOT EXISTS auth_audit_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type varchar(40) NOT NULL,
    outcome varchar(16) NOT NULL,
    actor_principal_id uuid NULL REFERENCES principal(id) ON DELETE SET NULL,
    subject_principal_id uuid NULL REFERENCES principal(id) ON DELETE SET NULL,
    app_id uuid NULL REFERENCES app(id) ON DELETE SET NULL,
    tenant_id uuid NULL REFERENCES tenant(id) ON DELETE SET NULL,
    context_id uuid NULL REFERENCES context(id) ON DELETE SET NULL,
    session_id uuid NULL REFERENCES session(id) ON DELETE SET NULL,
    credential_type varchar(40) NULL,
    grant_type varchar(120) NULL,
    token_id varchar(120) NULL,
    audience text NULL,
    scopes jsonb DEFAULT '[]'::jsonb NOT NULL,
    reason text NULL,
    request_id varchar(120) NULL,
    ip_address varchar(120) NULL,
    user_agent text NULL
) INHERITS (auditable);
COMMENT ON TABLE auth_audit_event IS 'Append-only auth security audit trail for token mint, exchange, refresh, revoke, and failure events. Raw secrets and bearer tokens are never stored.';

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_name ON app(name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_account_subject ON provider_account(provider, issuer, subject);
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM principal
        WHERE identity_id IS NOT NULL
        GROUP BY identity_id, kind
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot add uq_principal_identity_kind: duplicate non-null principal identity/kind rows exist';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'principal'::regclass
          AND conname = 'uq_principal_identity_kind'
    ) THEN
        IF EXISTS (
            SELECT 1
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema()
              AND c.relname = 'uq_principal_identity_kind'
        ) THEN
            ALTER TABLE principal
                ADD CONSTRAINT uq_principal_identity_kind UNIQUE USING INDEX uq_principal_identity_kind;
        ELSE
            ALTER TABLE principal
                ADD CONSTRAINT uq_principal_identity_kind UNIQUE (identity_id, kind);
        END IF;
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_context_app_name ON context(app_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_app_name ON tenant(app_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_app_name ON role(app_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_permission_app_name ON permission(app_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_permission ON role_permissions(role_id, permission_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_scope ON membership (
    principal_id,
    app_id,
    COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(context_id, '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_principal_role_scope ON principal_role (
    principal_id,
    role_id,
    COALESCE(context_id, '00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_provider_client ON auth_provider_client(app_id, provider, platform);

CREATE INDEX IF NOT EXISTS idx_provider_account_identity ON provider_account(identity_id);
CREATE INDEX IF NOT EXISTS idx_principal_identity ON principal(identity_id);
CREATE INDEX IF NOT EXISTS idx_membership_principal ON membership(principal_id);
CREATE INDEX IF NOT EXISTS idx_membership_app ON membership(app_id);
CREATE INDEX IF NOT EXISTS idx_principal_role_principal ON principal_role(principal_id);
CREATE INDEX IF NOT EXISTS idx_session_principal_id ON session(principal_id);
CREATE INDEX IF NOT EXISTS idx_session_app_id ON session(app_id);
CREATE INDEX IF NOT EXISTS idx_session_expires_at ON session(expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_session ON refresh_token(session_id);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_token(family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_principal ON refresh_token(principal_id);
CREATE INDEX IF NOT EXISTS idx_service_account_app ON service_account(app_id);
CREATE INDEX IF NOT EXISTS idx_service_account_principal ON service_account(principal_id);
CREATE INDEX IF NOT EXISTS idx_pat_principal_id ON personal_access_token(principal_id);
CREATE INDEX IF NOT EXISTS idx_pat_app_id ON personal_access_token(app_id);
CREATE INDEX IF NOT EXISTS idx_pat_token_hash ON personal_access_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_pat_revoked_at ON personal_access_token(revoked_at);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_created_at ON auth_audit_event(created_at);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_type_outcome ON auth_audit_event(event_type, outcome);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_actor ON auth_audit_event(actor_principal_id);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_subject ON auth_audit_event(subject_principal_id);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_app ON auth_audit_event(app_id);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_session ON auth_audit_event(session_id);

CREATE OR REPLACE TRIGGER app_updated BEFORE UPDATE ON app FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER identity_updated BEFORE UPDATE ON identity FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER provider_account_updated BEFORE UPDATE ON provider_account FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER principal_updated BEFORE UPDATE ON principal FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER context_updated BEFORE UPDATE ON context FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER tenant_updated BEFORE UPDATE ON tenant FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER membership_updated BEFORE UPDATE ON membership FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER role_updated BEFORE UPDATE ON role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER permission_updated BEFORE UPDATE ON permission FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER role_permissions_updated BEFORE UPDATE ON role_permissions FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER principal_role_updated BEFORE UPDATE ON principal_role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER auth_provider_client_updated BEFORE UPDATE ON auth_provider_client FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER session_updated BEFORE UPDATE ON session FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER refresh_token_updated BEFORE UPDATE ON refresh_token FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER service_account_updated BEFORE UPDATE ON service_account FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER personal_access_token_updated BEFORE UPDATE ON personal_access_token FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER signing_key_updated BEFORE UPDATE ON signing_key FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER auth_audit_event_updated BEFORE UPDATE ON auth_audit_event FOR EACH ROW EXECUTE FUNCTION on_update();

GRANT SELECT ON ALL TABLES IN SCHEMA heimdall TO anon;
