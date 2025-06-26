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
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL
) INHERITS (auditable);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    social_id TEXT NOT NULL,
    app_id UUID NOT NULL,
    provider VARCHAR(20) DEFAULT 'GOOGLE' NOT NULL,
    status VARCHAR(50) DEFAULT 'ONBOARDING' NOT NULL,
    UNIQUE (social_id, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    app_id UUID NOT NULL,
    UNIQUE (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(50) NOT NULL,
    app_id UUID NOT NULL,
    UNIQUE (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    app_id UUID NOT NULL,
    UNIQUE (name, app_id),
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE role_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    UNIQUE (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES permission(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE user_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    context_id UUID NOT NULL,
    UNIQUE (user_id, role_id, context_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    FOREIGN KEY (context_id) REFERENCES context(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE TABLE session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    app_id UUID NOT NULL,
    context_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (context_id) REFERENCES context(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) INHERITS (auditable);

CREATE INDEX idx_context_app_id ON context(app_id);
CREATE INDEX idx_user_app_id ON users(app_id);
CREATE INDEX idx_role_app_id ON role(app_id);
CREATE INDEX idx_permission_app_id ON permission(app_id);
CREATE INDEX idx_role_permissions_role_id ON role_permissions(role_id);
CREATE INDEX idx_role_permissions_permission_id ON role_permissions(permission_id);
CREATE INDEX idx_user_role_user_id ON user_role(user_id);
CREATE INDEX idx_user_role_role_id ON user_role(role_id);
CREATE INDEX idx_user_role_context_id ON user_role(context_id);
CREATE INDEX idx_session_user_id ON session(user_id);
CREATE INDEX idx_session_app_id ON session(app_id);
CREATE INDEX idx_session_context_id ON session(context_id);
CREATE INDEX idx_session_expires_at ON session(expires_at);

CREATE TRIGGER app_updated BEFORE UPDATE ON app FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER user_updated BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER context_updated BEFORE UPDATE ON context FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER role_updated BEFORE UPDATE ON role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER permission_updated BEFORE UPDATE ON permission FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER role_permissions_updated BEFORE UPDATE ON role_permissions FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER user_role_updated BEFORE UPDATE ON user_role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER session_updated BEFORE UPDATE ON session FOR EACH ROW EXECUTE FUNCTION on_update();



-- INSERT INTO "table" (id, name, social_id, provider, status, dob, gender, longitude, latitude, data, created_at, updated_at) VALUES (1, 'Shibasis Patnaik', '108751707621317214126', 'GOOGLE', 'ONBOARDING', '1990-01-01', 'Male', 77.5946, 12.9716, '{"email":"shibasispatnaik81@gmail.com","profile_picture":"https://images.shibasis.dev/shibasis.jpeg"}', '2025-03-06 11:19:39.175Z', '2025-03-06 11:19:51.431Z');
-- INSERT INTO "table" (id, name, social_id, provider, status, dob, gender, longitude, latitude, data, created_at, updated_at) VALUES (3, 'Shibasis Patnaik', '112031443045154093915', 'GOOGLE', 'ONBOARDING', '1990-01-01', 'Male', 77.5946, 12.9716, '{"email":"shibasis0801@gmail.com","profile_picture":"https://images.shibasis.dev/shibasis.jpeg"}', '2025-03-06 11:19:39.175Z', '2025-03-06 11:19:57.523Z');
-- INSERT INTO "table" (id, name, social_id, provider, status, dob, gender, longitude, latitude, data, created_at, updated_at) VALUES (2, 'Kedarnath', '104726993706978524594', 'GOOGLE', 'ONBOARDING', '1990-01-01', 'Male', 77.5946, 12.9716, '{"email":"karthikkaushal27@gmail.com","profile_picture":"https://images.shibasis.dev/kedarnath.jpeg"}', '2025-03-06 11:19:39.175Z', '2025-03-06 11:19:54.600Z');
-- INSERT INTO "table" (id, name, social_id, provider, status, dob, gender, longitude, latitude, data, created_at, updated_at) VALUES (5, 'Manab Mohanty', '108721170736337022348', 'GOOGLE', 'ONBOARDING', '1990-01-01', 'Male', 77.5946, 12.9716, '{"email":"manabmohanty44@gmail.com","profile_picture":"https://images.shibasis.dev/manab.jpeg"}', '2025-03-23 11:05:53.996Z', '2025-03-23 11:05:53.996Z');
