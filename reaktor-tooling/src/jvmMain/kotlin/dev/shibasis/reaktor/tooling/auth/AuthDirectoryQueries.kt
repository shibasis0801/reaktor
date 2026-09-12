package dev.shibasis.reaktor.tooling.auth

import java.util.UUID

enum class AuthDirectory(val label: String) {
    Principals("Users & principals"), Memberships("Memberships"), Sessions("Sessions"),
    RefreshTokens("Refresh tokens"), PersonalTokens("PATs"), ServiceAccounts("Service accounts"),
    Grants("Role grants"), Audit("Audit events"), AccessIssuance("Access issuance"),

    /**
     * One row per principal, with the credentials and grants that principal holds rolled up.
     *
     * The per-credential directories above each answer one question well and none of them answers
     * the first one asked of an identity system: *who and what can reach this, with what, and when
     * did each last work?* Reading that from [Principals], [ServiceAccounts], [PersonalTokens] and
     * [Sessions] separately means joining four result grids by eye. This does the join in the
     * database, where it belongs.
     */
    Identity("Identity"),

    /** Every role with the permissions it carries and the number of principals holding it. */
    Roles("Roles"),

    /** Every permission with the roles that carry it, so an orphan permission is visible as one. */
    Permissions("Permissions"),
}

/** Metadata projections of heimdall.sql. Credential material and arbitrary JSON never enter a result. */
object AuthDirectoryQueries {
    fun read(directory: AuthDirectory, appId: String = "", principalId: String = "", page: Int = 0): String {
        require(page in 0..10000) { "Page is out of range" }
        fun uuid(value: String): String = UUID.fromString(value.trim()).toString()
        val app = appId.takeIf(String::isNotBlank)?.let(::uuid)
        val principal = principalId.takeIf(String::isNotBlank)?.let(::uuid)
        val projection = when (directory) {
            AuthDirectory.Principals -> """
                SELECT p.id AS principal_id, p.kind, p.status, i.id AS identity_id, i.primary_email,
                    p.created_at, p.updated_at
                FROM heimdall.principal p LEFT JOIN heimdall.identity i ON i.id = p.identity_id
                WHERE 1=1
            """.trimIndent() + (app?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')" } ?: "") +
                (principal?.let { " AND p.id = '$it'" } ?: "") + " ORDER BY p.created_at DESC, p.id"
            AuthDirectory.Memberships -> scoped("""
                SELECT m.id, m.principal_id, m.app_id, a.name AS app, m.tenant_id, m.context_id, m.status, m.created_at
                FROM heimdall.membership m JOIN heimdall.app a ON a.id = m.app_id WHERE 1=1
            """, "m", app, principal) + " ORDER BY m.created_at DESC, m.id"
            AuthDirectory.Sessions -> scoped("""
                SELECT s.id AS session_id, s.principal_id, s.app_id, s.tenant_id, s.context_id,
                    s.created_at, s.expires_at, CASE WHEN s.expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'active' END AS status
                FROM heimdall.session s WHERE 1=1
            """, "s", app, principal) + " ORDER BY s.created_at DESC, s.id"
            AuthDirectory.RefreshTokens -> scoped("""
                SELECT r.id AS token_id, 'refresh' AS token_type, r.principal_id, r.session_id, r.family_id,
                    r.previous_token_id, s.app_id, r.created_at, r.expires_at, r.used_at, r.rotated_at, r.revoked_at,
                    CASE WHEN r.revoked_at IS NOT NULL THEN 'revoked' WHEN r.expires_at <= CURRENT_TIMESTAMP THEN 'expired'
                         WHEN r.used_at IS NOT NULL THEN 'used' ELSE 'active' END AS status
                FROM heimdall.refresh_token r JOIN heimdall.session s ON s.id = r.session_id WHERE 1=1
            """, "s", app, principal) + " ORDER BY r.created_at DESC, r.id"
            AuthDirectory.PersonalTokens -> scoped("""
                SELECT t.id AS token_id, 'PAT' AS token_type, t.name, t.principal_id, t.app_id, t.context_id,
                    t.scopes, t.allowed_audiences, t.created_at, t.expires_at, t.last_used_at, t.revoked_at,
                    CASE WHEN t.revoked_at IS NOT NULL THEN 'revoked' WHEN t.expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'active' END AS status
                FROM heimdall.personal_access_token t WHERE 1=1
            """, "t", app, principal) + " ORDER BY t.created_at DESC, t.id"
            AuthDirectory.ServiceAccounts -> scoped("""
                SELECT s.id, 'service' AS credential_type, s.name, s.principal_id, s.app_id,
                    s.client_id, s.auth_method, s.scopes, s.allowed_audiences, s.status, s.created_at, s.updated_at
                FROM heimdall.service_account s WHERE 1=1
            """, "s", app, principal) + " ORDER BY s.created_at DESC, s.id"
            AuthDirectory.Grants -> """
                SELECT g.id AS grant_id, g.principal_id, r.app_id, r.name AS role, g.context_id,
                    p.name AS permission, g.created_at
                FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id
                LEFT JOIN heimdall.role_permissions rp ON rp.role_id = r.id
                LEFT JOIN heimdall.permission p ON p.id = rp.permission_id WHERE 1=1
            """.trimIndent() + (app?.let { " AND r.app_id = '$it'" } ?: "") +
                (principal?.let { " AND g.principal_id = '$it'" } ?: "") + " ORDER BY g.created_at DESC, g.id, p.id"
            AuthDirectory.Identity -> """
                SELECT p.id AS principal_id, p.kind, p.status,
                    COALESCE(
                        (SELECT MIN(sa.name) FROM heimdall.service_account sa WHERE sa.principal_id = p.id),
                        i.primary_email, CAST(p.id AS VARCHAR)
                    ) AS name,
                    i.primary_email,
                    (SELECT MIN(sa.client_id) FROM heimdall.service_account sa WHERE sa.principal_id = p.id) AS service_client_id,
                    (SELECT COUNT(*) FROM heimdall.principal_role pr WHERE pr.principal_id = p.id) AS role_grants,
                    (SELECT COUNT(*) FROM heimdall.membership m WHERE m.principal_id = p.id) AS memberships,
                    (SELECT COUNT(*) FROM heimdall.session s WHERE s.principal_id = p.id
                        AND s.expires_at > CURRENT_TIMESTAMP) AS active_sessions,
                    (SELECT COUNT(*) FROM heimdall.personal_access_token t WHERE t.principal_id = p.id
                        AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)) AS active_pats,
                    (SELECT COUNT(*) FROM heimdall.provider_account pa WHERE pa.identity_id = p.identity_id) AS provider_accounts,
                    GREATEST(
                        (SELECT MAX(s.created_at) FROM heimdall.session s WHERE s.principal_id = p.id),
                        (SELECT MAX(t.last_used_at) FROM heimdall.personal_access_token t WHERE t.principal_id = p.id),
                        (SELECT MAX(e.created_at) FROM heimdall.auth_audit_event e
                            WHERE e.actor_principal_id = p.id OR e.subject_principal_id = p.id)
                    ) AS last_seen,
                    p.created_at
                FROM heimdall.principal p
                LEFT JOIN heimdall.identity i ON i.id = p.identity_id
                WHERE 1=1
            """.trimIndent() +
                (app?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')" } ?: "") +
                (principal?.let { " AND p.id = '$it'" } ?: "") +
                " ORDER BY last_seen DESC NULLS LAST, p.created_at DESC, p.id"
            AuthDirectory.Roles -> """
                SELECT r.id AS role_id, r.name AS role, a.name AS app, r.app_id,
                    (SELECT COUNT(*) FROM heimdall.role_permissions rp WHERE rp.role_id = r.id) AS permissions,
                    (SELECT COUNT(*) FROM heimdall.principal_role pr WHERE pr.role_id = r.id) AS principals,
                    r.created_at
                FROM heimdall.role r JOIN heimdall.app a ON a.id = r.app_id
                WHERE 1=1
            """.trimIndent() + (app?.let { " AND r.app_id = '$it'" } ?: "") +
                (principal?.let { " AND EXISTS (SELECT 1 FROM heimdall.principal_role pr WHERE pr.role_id = r.id AND pr.principal_id = '$it')" } ?: "") +
                " ORDER BY a.name, r.name, r.id"
            AuthDirectory.Permissions -> """
                SELECT pm.id AS permission_id, pm.name AS permission, a.name AS app, pm.app_id,
                    (SELECT COUNT(*) FROM heimdall.role_permissions rp WHERE rp.permission_id = pm.id) AS roles,
                    (SELECT COUNT(*) FROM heimdall.principal_role pr
                        JOIN heimdall.role_permissions rp ON rp.role_id = pr.role_id
                        WHERE rp.permission_id = pm.id) AS grants,
                    pm.created_at
                FROM heimdall.permission pm JOIN heimdall.app a ON a.id = pm.app_id
                WHERE 1=1
            """.trimIndent() + (app?.let { " AND pm.app_id = '$it'" } ?: "") +
                (principal?.let { " AND EXISTS (SELECT 1 FROM heimdall.principal_role pr JOIN heimdall.role_permissions rp ON rp.role_id = pr.role_id WHERE rp.permission_id = pm.id AND pr.principal_id = '$it')" } ?: "") +
                " ORDER BY a.name, pm.name, pm.id"
            AuthDirectory.Audit, AuthDirectory.AccessIssuance -> """
                SELECT e.id, e.created_at, e.event_type, e.outcome, e.actor_principal_id, e.subject_principal_id,
                    e.app_id, e.tenant_id, e.context_id, e.session_id, e.credential_type, e.grant_type,
                    e.token_id, e.audience, e.scopes, e.request_id
                FROM heimdall.auth_audit_event e WHERE 1=1
            """.trimIndent() + (app?.let { " AND e.app_id = '$it'" } ?: "") +
                (principal?.let { " AND (e.subject_principal_id = '$it' OR e.actor_principal_id = '$it')" } ?: "") +
                (if (directory == AuthDirectory.AccessIssuance) " AND e.event_type IN ('TOKEN_MINT', 'TOKEN_EXCHANGE')" else "") +
                " ORDER BY e.created_at DESC, e.id"
        }
        return "$projection LIMIT 200 OFFSET ${page * 200}"
    }

    private fun scoped(sql: String, alias: String, app: String?, principal: String?): String = sql.trimIndent() +
        (app?.let { " AND $alias.app_id = '$it'" } ?: "") +
        (principal?.let { " AND $alias.principal_id = '$it'" } ?: "")
}
