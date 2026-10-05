package dev.shibasis.reaktor.tooling.auth

import dev.shibasis.reaktor.tooling.database.BoundQuery

data class GrantScope(val tenantId: String? = null, val contextId: String? = null)

object AuthTenancyQueries {
    const val PageSize: Int = 200
    const val CatalogueRows: Int = 500

    private const val DisplayName = """COALESCE(
        (SELECT MIN(sa.name) FROM heimdall.service_account sa WHERE sa.principal_id = p.id),
        i.primary_email,
        (SELECT MIN(pa.email) FROM heimdall.provider_account pa WHERE pa.identity_id = p.identity_id),
        CAST(p.id AS VARCHAR))"""

    fun apps(): String = """
        SELECT a.id AS app_id, a.name AS app,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.membership m WHERE m.app_id = a.id) AS members,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.membership m WHERE m.app_id = a.id AND m.status <> 'ACTIVE') AS inactive_members,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.tenant t WHERE t.app_id = a.id) AS tenants,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.context c WHERE c.app_id = a.id) AS contexts,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.role r WHERE r.app_id = a.id) AS roles,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.permission pm WHERE pm.app_id = a.id) AS permissions,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id WHERE r.app_id = a.id) AS grants,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.session s WHERE s.app_id = a.id AND s.expires_at > CURRENT_TIMESTAMP) AS active_sessions,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.auth_provider_client c WHERE c.app_id = a.id AND c.enabled = TRUE) AS clients,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.service_account sa WHERE sa.app_id = a.id) AS service_accounts,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.auth_audit_event e WHERE e.app_id = a.id
                AND e.created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY) AS events_7d,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.auth_audit_event e WHERE e.app_id = a.id
                AND e.created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY AND e.outcome NOT ILIKE 'succ%') AS failures_7d,
            a.created_at
        FROM heimdall.app a
        ORDER BY members DESC, a.name
        LIMIT $PageSize
    """.trimIndent()

    fun services(): String = """
        SELECT sa.id AS service_account_id, sa.name, sa.client_id, sa.auth_method, sa.scopes, sa.allowed_audiences, sa.status,
            sa.principal_id, sa.app_id, a.name AS app,
            (SELECT MAX(e.created_at) FROM heimdall.auth_audit_event e
                WHERE (e.actor_principal_id = sa.principal_id OR e.subject_principal_id = sa.principal_id) AND e.outcome ILIKE 'succ%') AS last_used_at,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.auth_audit_event e
                WHERE (e.actor_principal_id = sa.principal_id OR e.subject_principal_id = sa.principal_id)
                AND e.outcome ILIKE 'succ%' AND e.created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY) AS tokens_7d,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.auth_audit_event e
                WHERE (e.actor_principal_id = sa.principal_id OR e.subject_principal_id = sa.principal_id)
                AND e.outcome NOT ILIKE 'succ%' AND e.created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY) AS failures_7d,
            sa.created_at, sa.updated_at
        FROM heimdall.service_account sa LEFT JOIN heimdall.app a ON a.id = sa.app_id
        ORDER BY sa.name
        LIMIT $PageSize
    """.trimIndent()

    fun tenants(appId: String = ""): String {
        val app = uuidOrNull(appId)
        return """
            SELECT t.id AS tenant_id, t.name AS tenant, t.app_id, a.name AS app, t.status,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.membership m WHERE m.tenant_id = t.id) AS members,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.membership m WHERE m.tenant_id = t.id AND m.status <> 'ACTIVE') AS inactive_members,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.membership m WHERE m.tenant_id = t.id AND m.app_id <> t.app_id) AS foreign_members,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal_role g WHERE g.tenant_id = t.id) AS grants,
                (SELECT CAST(COUNT(DISTINCT g.principal_id) AS BIGINT) FROM heimdall.principal_role g WHERE g.tenant_id = t.id) AS grantees,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.session s WHERE s.tenant_id = t.id AND s.expires_at > CURRENT_TIMESTAMP) AS active_sessions,
                t.created_at
            FROM heimdall.tenant t JOIN heimdall.app a ON a.id = t.app_id
            WHERE 1=1${app?.let { " AND t.app_id = '$it'" } ?: ""}
            ORDER BY a.name, members DESC, t.name
            LIMIT $PageSize
        """.trimIndent()
    }

    fun contexts(appId: String = ""): String {
        val app = uuidOrNull(appId)
        return """
            SELECT c.id AS context_id, c.name AS context, c.app_id, a.name AS app,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.membership m WHERE m.context_id = c.id) AS members,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal_role g WHERE g.context_id = c.id) AS grants,
                c.created_at
            FROM heimdall.context c JOIN heimdall.app a ON a.id = c.app_id
            WHERE 1=1${app?.let { " AND c.app_id = '$it'" } ?: ""}
            ORDER BY a.name, c.name
            LIMIT $PageSize
        """.trimIndent()
    }

    fun roles(appId: String = ""): String {
        val app = uuidOrNull(appId)
        return """
            SELECT r.id AS role_id, r.name AS role, r.app_id, a.name AS app,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.role_permissions rp JOIN heimdall.permission pm ON pm.id = rp.permission_id
                    WHERE rp.role_id = r.id AND pm.app_id = r.app_id) AS permissions,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.role_permissions rp JOIN heimdall.permission pm ON pm.id = rp.permission_id
                    WHERE rp.role_id = r.id AND pm.app_id <> r.app_id) AS foreign_permissions,
                (SELECT CAST(COUNT(DISTINCT g.principal_id) AS BIGINT) FROM heimdall.principal_role g WHERE g.role_id = r.id) AS holders,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal_role g WHERE g.role_id = r.id AND g.tenant_id IS NULL) AS app_wide_grants,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal_role g WHERE g.role_id = r.id AND g.tenant_id IS NOT NULL) AS tenant_grants,
                (SELECT CAST(COUNT(DISTINCT g.tenant_id) AS BIGINT) FROM heimdall.principal_role g WHERE g.role_id = r.id) AS tenants,
                r.created_at
            FROM heimdall.role r JOIN heimdall.app a ON a.id = r.app_id
            WHERE 1=1${app?.let { " AND r.app_id = '$it'" } ?: ""}
            ORDER BY a.name, r.name
            LIMIT $CatalogueRows
        """.trimIndent()
    }

    fun permissions(appId: String = ""): String {
        val app = uuidOrNull(appId)
        return """
            SELECT pm.id AS permission_id, pm.name AS permission, pm.app_id, a.name AS app,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.role_permissions rp JOIN heimdall.role r ON r.id = rp.role_id
                    WHERE rp.permission_id = pm.id AND r.app_id = pm.app_id) AS roles,
                (SELECT CAST(COUNT(DISTINCT g.principal_id) AS BIGINT) FROM heimdall.principal_role g
                    JOIN heimdall.role_permissions rp ON rp.role_id = g.role_id JOIN heimdall.role r ON r.id = g.role_id
                    WHERE rp.permission_id = pm.id AND r.app_id = pm.app_id) AS holders,
                pm.created_at
            FROM heimdall.permission pm JOIN heimdall.app a ON a.id = pm.app_id
            WHERE 1=1${app?.let { " AND pm.app_id = '$it'" } ?: ""}
            ORDER BY a.name, pm.name
            LIMIT $CatalogueRows
        """.trimIndent()
    }

    fun matrix(appId: String = ""): String {
        val app = uuidOrNull(appId)
        return """
            SELECT r.id AS role_id, r.name AS role, a.name AS app, r.app_id, pm.id AS permission_id, pm.name AS permission
            FROM heimdall.role_permissions rp
            JOIN heimdall.role r ON r.id = rp.role_id
            JOIN heimdall.permission pm ON pm.id = rp.permission_id AND pm.app_id = r.app_id
            JOIN heimdall.app a ON a.id = r.app_id
            WHERE 1=1${app?.let { " AND r.app_id = '$it'" } ?: ""}
            ORDER BY a.name, r.name, pm.name
            LIMIT $CatalogueRows
        """.trimIndent()
    }

    fun grants(appId: String = "", tenantId: String = "", principalId: String = ""): String {
        val app = uuidOrNull(appId)
        val tenant = uuidOrNull(tenantId)
        val principal = uuidOrNull(principalId)
        return """
            SELECT g.id AS grant_id, g.principal_id, p.kind, $DisplayName AS name,
                r.id AS role_id, r.name AS role, r.app_id, ra.name AS app,
                g.tenant_id, t.name AS tenant, g.context_id, c.name AS context,
                CASE WHEN g.tenant_id IS NULL AND g.context_id IS NULL THEN 'app' WHEN g.context_id IS NULL THEN 'tenant' ELSE 'context' END AS scope,
                CASE
                    WHEN t.id IS NOT NULL AND t.app_id <> r.app_id THEN 'tenant belongs to another app'
                    WHEN c.id IS NOT NULL AND c.app_id <> r.app_id THEN 'context belongs to another app'
                    WHEN r.name = 'superadmin' AND g.tenant_id IS NULL THEN 'superadmin only applies where granted in a tenant'
                    WHEN NOT EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = g.principal_id AND m.app_id = r.app_id)
                        THEN 'holder is not a member of this app'
                    WHEN g.tenant_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM heimdall.membership m
                        WHERE m.principal_id = g.principal_id AND m.app_id = r.app_id AND m.tenant_id = g.tenant_id)
                        THEN 'holder is not a member of this tenant'
                    WHEN g.context_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM heimdall.membership m
                        WHERE m.principal_id = g.principal_id AND m.app_id = r.app_id AND m.context_id = g.context_id)
                        THEN 'holder is not a member of this context'
                    WHEN p.status <> 'ACTIVE' THEN 'holder is not active'
                    ELSE ''
                END AS problem,
                g.created_at
            FROM heimdall.principal_role g
            JOIN heimdall.role r ON r.id = g.role_id
            JOIN heimdall.app ra ON ra.id = r.app_id
            LEFT JOIN heimdall.tenant t ON t.id = g.tenant_id
            LEFT JOIN heimdall.context c ON c.id = g.context_id
            LEFT JOIN heimdall.principal p ON p.id = g.principal_id
            LEFT JOIN heimdall.identity i ON i.id = p.identity_id
            WHERE 1=1
        """.trimIndent() +
            (app?.let { " AND r.app_id = '$it'" } ?: "") +
            (tenant?.let { " AND (g.tenant_id = '$it' OR g.tenant_id IS NULL)" } ?: "") +
            (principal?.let { " AND g.principal_id = '$it'" } ?: "") +
            " ORDER BY ra.name, r.name, t.name, g.created_at LIMIT $CatalogueRows"
    }

    fun memberships(principalId: String): String {
        val principal = requireNotNull(uuidOrNull(principalId)) { "Memberships need a principal" }
        val applies = applies("g", "r", "m.tenant_id", "m.context_id")
        return """
            SELECT m.id AS membership_id, a.name AS app, m.app_id, m.status, m.tenant_id, t.name AS tenant, m.context_id, c.name AS context,
                CASE
                    WHEN t.id IS NOT NULL AND t.app_id <> m.app_id THEN 'tenant belongs to another app'
                    WHEN c.id IS NOT NULL AND c.app_id <> m.app_id THEN 'context belongs to another app'
                    WHEN t.id IS NOT NULL AND t.status <> 'ACTIVE' THEN 'tenant is not active'
                    ELSE ''
                END AS problem,
                (SELECT STRING_AGG(DISTINCT r.name, ', ') FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id
                    WHERE g.principal_id = m.principal_id AND r.app_id = m.app_id AND $applies) AS roles,
                (SELECT STRING_AGG(DISTINCT pm.name, ', ') FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id
                    JOIN heimdall.role_permissions rp ON rp.role_id = r.id
                    JOIN heimdall.permission pm ON pm.id = rp.permission_id AND pm.app_id = r.app_id
                    WHERE g.principal_id = m.principal_id AND r.app_id = m.app_id AND $applies) AS permissions,
                m.created_at
            FROM heimdall.membership m
            JOIN heimdall.app a ON a.id = m.app_id
            LEFT JOIN heimdall.tenant t ON t.id = m.tenant_id
            LEFT JOIN heimdall.context c ON c.id = m.context_id
            WHERE m.principal_id = '$principal'
            ORDER BY a.name, t.name
        """.trimIndent()
    }

    fun effectivePermissions(principalId: String, appId: String, scope: GrantScope = GrantScope()): String {
        val principal = requireNotNull(uuidOrNull(principalId)) { "Effective permissions need a principal" }
        val app = requireNotNull(uuidOrNull(appId)) { "Effective permissions need an app" }
        val tenant = scope.tenantId?.let(::uuidOrNull)
        val context = scope.contextId?.let(::uuidOrNull)
        val superadmin = tenant?.let { "r.name = 'superadmin' AND (g.tenant_id IS NULL OR g.tenant_id <> '$it')" } ?: "r.name = 'superadmin'"
        val otherTenant = tenant?.let { "g.tenant_id IS NOT NULL AND g.tenant_id <> '$it'" } ?: "g.tenant_id IS NOT NULL"
        val otherContext = context?.let { "g.context_id IS NOT NULL AND g.context_id <> '$it'" } ?: "g.context_id IS NOT NULL"
        return """
            SELECT g.id AS grant_id, r.name AS role, pm.name AS permission, g.tenant_id, t.name AS tenant, g.context_id, c.name AS context,
                CASE
                    WHEN $superadmin THEN 'superadmin applies only where it is granted in this tenant'
                    WHEN $otherTenant THEN 'granted in another tenant'
                    WHEN $otherContext THEN 'granted in another context'
                    ELSE ''
                END AS excluded_because
            FROM heimdall.principal_role g
            JOIN heimdall.role r ON r.id = g.role_id
            LEFT JOIN heimdall.role_permissions rp ON rp.role_id = r.id
            LEFT JOIN heimdall.permission pm ON pm.id = rp.permission_id AND pm.app_id = r.app_id
            LEFT JOIN heimdall.tenant t ON t.id = g.tenant_id
            LEFT JOIN heimdall.context c ON c.id = g.context_id
            WHERE g.principal_id = '$principal' AND r.app_id = '$app'
            ORDER BY excluded_because, r.name, pm.name
            LIMIT $CatalogueRows
        """.trimIndent()
    }

    fun people(appId: String = "", tenantId: String = "", kind: String = "", search: String = "", page: Int = 0): BoundQuery {
        require(page in 0..10_000) { "Page is out of range" }
        val app = uuidOrNull(appId)
        val tenant = uuidOrNull(tenantId)
        val kindCode = filterCode(kind)?.uppercase()
        val (terms, parameters) = searchConditions(search, listOf("CAST(p.id AS VARCHAR)", "CAST(p.identity_id AS VARCHAR)", "i.primary_email", "p.kind", "p.status")) { key ->
            fun provider(column: String) = "EXISTS (SELECT 1 FROM heimdall.provider_account pa WHERE pa.identity_id = p.identity_id AND ${match(column)})"
            fun membership(column: String) = "EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND ${match(column)})"
            fun service(column: String) = "EXISTS (SELECT 1 FROM heimdall.service_account sa WHERE sa.principal_id = p.id AND ${match(column)})"
            fun role() = "EXISTS (SELECT 1 FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id WHERE g.principal_id = p.id AND ${match("r.name")})"
            when (key) {
                "name" -> match(DisplayName)
                "email" -> "${match("i.primary_email")} OR ${provider("pa.email")}"
                "provider" -> provider("pa.provider")
                "kind" -> match("p.kind")
                "role" -> role()
                "user", "principal" -> match("CAST(p.id AS VARCHAR)")
                "age" -> age("p.created_at")
                "is" -> when (value.lowercase()) {
                    "active" -> "p.status = 'ACTIVE'"
                    "user", "service", "agent" -> "p.kind = '${value.uppercase()}'"
                    else -> null
                }
                else -> if (key.isNotBlank()) null else listOf(match("CAST(p.id AS VARCHAR)", "CAST(p.identity_id AS VARCHAR)", "i.primary_email", "p.kind", "p.status"),
                    provider("pa.email"), provider("pa.provider"), membership("CAST(m.profile AS VARCHAR)"), service("sa.name"), service("sa.client_id"), role()).joinToString(" OR ")
            }
        }
        return BoundQuery("""
            SELECT p.id AS principal_id, p.identity_id, p.kind, p.status, $DisplayName AS name,
                COALESCE(i.primary_email, (SELECT MIN(pa.email) FROM heimdall.provider_account pa WHERE pa.identity_id = p.identity_id)) AS primary_email,
                (SELECT STRING_AGG(DISTINCT pa.provider, ', ') FROM heimdall.provider_account pa WHERE pa.identity_id = p.identity_id) AS providers,
                (SELECT STRING_AGG(DISTINCT a.name, ', ') FROM heimdall.membership m JOIN heimdall.app a ON a.id = m.app_id WHERE m.principal_id = p.id) AS apps,
                (SELECT STRING_AGG(DISTINCT t.name, ', ') FROM heimdall.membership m JOIN heimdall.tenant t ON t.id = m.tenant_id WHERE m.principal_id = p.id) AS tenants,
                (SELECT STRING_AGG(DISTINCT r.name, ', ') FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id WHERE g.principal_id = p.id) AS roles,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal_role g WHERE g.principal_id = p.id) AS role_grants,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.session s WHERE s.principal_id = p.id AND s.expires_at > CURRENT_TIMESTAMP) AS active_sessions,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.personal_access_token t WHERE t.principal_id = p.id
                    AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)) AS active_pats,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal sibling WHERE sibling.identity_id = p.identity_id AND sibling.id <> p.id) AS linked_principals,
                GREATEST(
                    (SELECT MAX(s.created_at) FROM heimdall.session s WHERE s.principal_id = p.id),
                    (SELECT MAX(COALESCE(rt.used_at, rt.created_at)) FROM heimdall.refresh_token rt WHERE rt.principal_id = p.id),
                    (SELECT MAX(t.last_used_at) FROM heimdall.personal_access_token t WHERE t.principal_id = p.id),
                    (SELECT MAX(e.created_at) FROM heimdall.auth_audit_event e
                        WHERE (e.actor_principal_id = p.id OR e.subject_principal_id = p.id) AND e.outcome ILIKE 'succ%')
                ) AS last_seen_at,
                p.created_at,
                COUNT(*) OVER () AS total_matches
            FROM heimdall.principal p
            LEFT JOIN heimdall.identity i ON i.id = p.identity_id
            WHERE 1=1
        """.trimIndent() +
            (app?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')" } ?: "") +
            (tenant?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.tenant_id = '$it')" } ?: "") +
            (kindCode?.let { " AND p.kind = '$it'" } ?: "") +
            terms +
            " ORDER BY last_seen_at DESC NULLS LAST, p.created_at DESC, p.id LIMIT $PageSize OFFSET ${page * PageSize}", parameters)
    }

    private fun applies(grant: String, role: String, tenant: String, context: String): String =
        "($grant.tenant_id IS NULL OR $grant.tenant_id = $tenant) AND ($grant.context_id IS NULL OR $grant.context_id = $context)" +
            " AND ($role.name <> 'superadmin' OR $grant.tenant_id = $tenant)"
}
