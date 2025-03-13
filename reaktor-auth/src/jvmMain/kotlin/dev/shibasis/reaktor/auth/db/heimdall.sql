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
GRANT ALL ON SCHEMA heimdall TO public;
GRANT USAGE ON SCHEMA heimdall TO anon;
GRANT SELECT ON ALL TABLES IN SCHEMA heimdall TO anon;
ALTER DEFAULT PRIVILEGES IN SCHEMA heimdall GRANT SELECT ON TABLES TO anon;

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

CREATE TABLE app (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
) INHERITS (auditable);

CREATE TABLE "user" (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    social_id TEXT NOT NULL,
    app_id INT NOT NULL,
    provider VARCHAR(20) DEFAULT 'GOOGLE' NOT NULL,
    status VARCHAR(50) DEFAULT 'ONBOARDING' NOT NULL,
    unique (social_id, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE context (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    app_id INT NOT NULL,
    unique (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE role (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    app_id INT NOT NULL,
    unique (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE permission (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    app_id INT NOT NULL,
    unique (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE role_permissions (
    id SERIAL PRIMARY KEY,
    role_id INT NOT NULL,
    permission_id INT NOT NULL,
    unique (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES permission(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE user_role (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL,
    role_id INT NOT NULL,
    context_id INT NOT NULL,
    unique (user_id, role_id, context_id),
    FOREIGN KEY (user_id) REFERENCES "user"(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (context_id) REFERENCES context(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE session (
    id UUID PRIMARY KEY,
    user_id INT NOT NULL,
    app_id INT NOT NULL,
    context_id INT NOT NULL,
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

CREATE OR REPLACE TRIGGER app_updated BEFORE UPDATE ON "user" FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER user_updated BEFORE UPDATE ON "user" FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER context_updated BEFORE UPDATE ON context FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER role_updated BEFORE UPDATE ON role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER permission_updated BEFORE UPDATE ON permission FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER role_permissions_updated BEFORE UPDATE ON role_permissions FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER user_role_updated BEFORE UPDATE ON user_role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE OR REPLACE TRIGGER session_updated BEFORE UPDATE ON session FOR EACH ROW EXECUTE FUNCTION on_update();
