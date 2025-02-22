/*
Example of targets:

Mehmaan
    Host
        EventGroup
            Event
                User <- role
    role <- permission

FastOrder
    Owner
        Restaurant
            User <- role
    role <- permission

Resources:
    https://chatgpt.com/c/66e56079-33cc-8012-95e0-05f44d0a2b3e
    https://supabase.com/docs/guides/database/postgres/custom-claims-and-role-based-access-control-rbac
    https://www.youtube.com/watch?v=b6VhN_HHDiQ&t=2827s

Multi Tenant role Based Access Control

Requirements:
An app can have multiple entities.
Mehmaan has many events and FoodKafka has many restaurants.
The same user can have different roles in different context in the same app.
The role definitions cannot change inside of an app, but a user's roles can change.

roles and permissions are scoped to an app and are unique within it.
roles and users are scoped to an context and are unique within it.

Need to add a mechanism for users to join their accounts across apps.
*/

DROP SCHEMA IF EXISTS heimdall CASCADE;
CREATE SCHEMA IF NOT EXISTS heimdall;
SET search_path TO heimdall;

CREATE TABLE auditable (
    data jsonb DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE OR REPLACE FUNCTION on_update()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER set_updated_at_auditable
BEFORE UPDATE ON auditable
FOR EACH ROW EXECUTE FUNCTION on_update();

CREATE TABLE app (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
) INHERITS (auditable);

CREATE TABLE "user" (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    social_id TEXT NOT NULL,
    app_id BIGINT NOT NULL,
    provider VARCHAR(20) DEFAULT 'GOOGLE' NOT NULL,
    status VARCHAR(50) DEFAULT 'ONBOARDING' NOT NULL,
    unique (social_id, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE context (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    app_id BIGINT NOT NULL,
    unique (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE role (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    app_id BIGINT NOT NULL,
    unique (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE permission (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    app_id BIGINT NOT NULL,
    unique (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE role_permissions (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    unique (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES permission(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    context_id BIGINT NOT NULL,
    unique (user_id, role_id, context_id),
    FOREIGN KEY (user_id) REFERENCES "user"(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (context_id) REFERENCES context(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE session (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    app_id BIGINT NOT NULL,
    context_id BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (context_id) REFERENCES context(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES "user"(id) ON DELETE CASCADE,
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE INDEX idx_context_app_id ON context(app_id);
CREATE INDEX idx_user_app_id ON "user"(app_id);
CREATE INDEX idx_role_app_id ON role(app_id);
CREATE INDEX idx_permission_app_id ON permission(app_id);
CREATE INDEX idx_role_permissions_role_id ON role_permissions(role_id);
CREATE INDEX idx_role_permissions_permission_id ON role_permissions(permission_id);
CREATE INDEX idx_user_role_user_id ON user_role(user_id);
CREATE INDEX idx_user_role_role_id ON user_role(role_id);
CREATE INDEX idx_user_role_context_id ON user_role(context_id);

INSERT INTO app (id, name, data, created_at, updated_at) VALUES
    (0, 'root', '{}'::jsonb, NOW(), NOW()),
    (1, 'BestBuds', '{}'::jsonb, NOW(), NOW()),
    (2, 'Mehmaan', '{}'::jsonb, NOW(), NOW()),
    (3, 'Manna', '{}'::jsonb, NOW(), NOW());

INSERT INTO "user" (id, name, social_id, app_id, provider, status, data, created_at, updated_at) VALUES
    (0, 'root', 'social_id_here', 0, 'GOOGLE', 'ONBOARDING', '{}'::jsonb, NOW(), NOW());

INSERT INTO role (id, name, app_id, data, created_at, updated_at) VALUES
    (0, 'root', 0, '{}'::jsonb, NOW(), NOW());
