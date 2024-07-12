package dev.shibasis.reaktor.auth.apps

import dev.shibasis.reaktor.auth.Apps
import dev.shibasis.reaktor.auth.Roles
import dev.shibasis.reaktor.auth.Users
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID


enum class WalnutRole {
    SUPER_ADMIN,
    ADMIN,
    STAFF,
    TEACHER,
    PARENT;
    override fun toString(): String {
        return name.lowercase()
    }
}

enum class WalnutPermission {
    ADMISSIONS_FILL,
    ADMISSIONS_VIEW,
    ADMISSIONS_ADMIT,
    ATTENDANCE_MARK,
    ATTENDANCE_VIEW;

    override fun toString(): String {
        return name.lowercase()
    }
}

object Walnut: RBACApp {
    internal val walnutPermissions = hashMapOf<WalnutRole, List<WalnutPermission>>()
    init {
        walnutPermissions[WalnutRole.SUPER_ADMIN] = listOf(
            WalnutPermission.ADMISSIONS_FILL,
            WalnutPermission.ADMISSIONS_VIEW,
            WalnutPermission.ADMISSIONS_ADMIT,

            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.ADMIN] = listOf(
            WalnutPermission.ADMISSIONS_FILL,
            WalnutPermission.ADMISSIONS_VIEW,
            WalnutPermission.ADMISSIONS_ADMIT,

            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.STAFF] = listOf(
            WalnutPermission.ADMISSIONS_FILL,
            WalnutPermission.ADMISSIONS_VIEW,

            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.TEACHER] = listOf(
            WalnutPermission.ATTENDANCE_MARK,
            WalnutPermission.ATTENDANCE_VIEW
        )
        walnutPermissions[WalnutRole.PARENT] = listOf(
            WalnutPermission.ATTENDANCE_VIEW,
            WalnutPermission.ADMISSIONS_FILL,
        )
    }

    override suspend fun setup() {
        val exists = transaction { Apps.selectAll().where { Apps.name eq "Cherry Melon" }.firstOrNull() }
        if (exists != null) return

        transaction {
            Apps.insert {
                it[name] = "Cherry Melon"
//                it[data] = "{\"description\": \"A school management system\"}"
            }
            Users.insert {
                it[id] = UUID.nameUUIDFromBytes("5myuPd6Bd1Z7uyIQLM10d3UhAfj2".encodeToByteArray())
                it[email] = "shibasis0801@gmail.com"
                it[appId] = 0
                it[name] = "Shibasis Patnaik"
            }

            val id = Apps.selectAll().where { Apps.name eq "Cherry Melon" }.first()[Apps.id]
            WalnutRole.entries.forEach { role ->
                Roles.insert {
                    it[name] = role.toString()
                    it[appId] = id.value
                    it[permissions] = role.permissions.map(WalnutPermission::toString)
                }
            }
        }
    }

    override suspend fun teardown() {

    }
}


val WalnutRole.permissions: List<WalnutPermission>
    get() = Walnut.walnutPermissions[this] ?: emptyList()

/*
INSERT INTO roles (name, app_id, permissions)
VALUES
    ('super_admin', 15, '{"admissions_fill", "admissions_view","admissions_admit", "attendance_mark", "attendance_view"}');
    ('admin', 15, '{"admissions_fill", "admissions_view", "admissions_admit", "attendance_mark", "attendance_view"}'),
    ('staff', 15, '{"admissions_fill", "admissions_view", "attendance_mark", "attendance_view"}'),
    ('teacher', 15, '{"attendance_mark", "attendance_view"}'),
    ('parent', 15, '{"attendance_view", "admissions_fill"}');

 */