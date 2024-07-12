/*
Example of Apps:

Mehmaan
    Host
        EventGroup
            Event
                User <- Role
    Role <- Permission

FoodKafka
    Owner
        Restaurant
            User <- Role
    Role <- Permission

Resources:
    https://supabase.com/docs/guides/database/postgres/custom-claims-and-role-based-access-control-rbac


Multi Tenant Role Based Access Control

Requirements:
An app can have multiple entities.
Mehmaan has many events and FoodKafka has many restaurants.
The same user can have different roles in different entities in the same app.
The role definitions cannot change inside of an app, but a user's roles can change.

Roles and permissions are scoped to an app and are unique within it.


*/

CREATE TABLE IF NOT EXISTS App(
    id BIGSERIAL PRIMARY KEY,
    name varchar(100),
    data json,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS Entity(
    id BIGSERIAL PRIMARY KEY,
    name varchar(100),
    app_id BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (app_id) REFERENCES App(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS "User"(
    id BIGSERIAL PRIMARY KEY,
    name varchar(100),
    uuid uuid,
    app_id BIGSERIAL,
    data json,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (app_id) REFERENCES App(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS Role(
    id BIGSERIAL PRIMARY KEY,
    name varchar(50),
    app_id BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (app_id) REFERENCES App(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS Permission(
    id BIGSERIAL PRIMARY KEY,
    name varchar(100),
    app_id BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (app_id) REFERENCES App(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS RolePermissions(
    id BIGSERIAL PRIMARY KEY,
    role_id BIGSERIAL,
    permission_id BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (role_id) REFERENCES Role(id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES Permission(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS UserRole(
    id BIGSERIAL PRIMARY KEY,
    user_id BIGSERIAL,
    role_id BIGSERIAL,
    entity_id BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES "User"(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES Role(id) ON DELETE CASCADE,
    FOREIGN KEY (entity_id) REFERENCES Entity(id) ON DELETE CASCADE
);

CREATE OR REPLACE FUNCTION on_update()
RETURNS TRIGGER AS $$
BEGIN
   NEW.updated_at = NOW();
   RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER set_updated_at_apps BEFORE UPDATE ON App FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER set_updated_at_entities BEFORE UPDATE ON Entity FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER set_updated_at_users BEFORE UPDATE ON "User" FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER set_updated_at_roles BEFORE UPDATE ON Role FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER set_updated_at_permissions BEFORE UPDATE ON Permission FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER set_updated_at_role_permissions BEFORE UPDATE ON RolePermissions FOR EACH ROW EXECUTE FUNCTION on_update();
CREATE TRIGGER set_updated_at_user_roles BEFORE UPDATE ON UserRole FOR EACH ROW EXECUTE FUNCTION on_update();