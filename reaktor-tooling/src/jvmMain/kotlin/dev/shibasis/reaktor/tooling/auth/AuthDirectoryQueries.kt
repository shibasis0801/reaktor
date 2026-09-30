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

    /**
     * Signing-key metadata — never `private_key_ref`, never key material.
     *
     * The verifier accepts a current and a previous key. Which row is which is a fact about
     * production that nothing in the workspace could previously read, so a key generated at
     * startup because the configured one was missing looked exactly like a key that was meant
     * to be there.
     */
    SigningKeys("Signing keys"),

    /**
     * Population shape rather than population size: principals by kind and by status, identities by
     * status, the provider mix, and the outcome split of the last seven days of audit events.
     *
     * Every other directory answers "is this row correct". This one answers "what does this system
     * look like", which is the question asked by everyone who is not debugging.
     */
    Composition("Composition"),

    /**
     * Principals that are no longer active but still hold a usable refresh token.
     *
     * Refresh rotation checks token expiry and reuse; it does not re-check principal, identity or
     * membership status. Disabling someone therefore does not end their access on its own — they
     * keep minting access tokens until the refresh token itself expires. This is that population,
     * counted.
     */
    StaleRefresh("Disabled principals with live refresh"),

    /**
     * Live personal access tokens carrying wildcard or auth-administrative scope.
     *
     * Mint authority is gated on who may mint, not on what may be minted, so a mint-capable token
     * can issue one of these for any principal. The schema records no creator, so the
     * "minted for someone other than its creator" half of that finding cannot be computed here and
     * is deliberately absent rather than approximated.
     */
    OverScopedTokens("Over-scoped tokens"),

    /**
     * Access that exists and is not used: roles nobody holds, tokens never presented, and
     * principals holding grants with no audit activity inside the window.
     *
     * Usage is evidenced by `auth_audit_event` and by `last_used_at`, so the finding is only ever
     * as good as the retention behind it — a ninety-day claim needs ninety days of trail.
     */
    UnusedAccess("Unused access"),

    /**
     * The audit trail narrowed to what did not succeed, newest first.
     *
     * The trail is already good — append-only, carrying reason, address and user agent. What it was
     * missing is a default that matches the question people actually bring to it, which is never
     * "show me everything that happened" and almost always "why did this one thing fail".
     */
    AuthFailures("Failures"),

    /**
     * Every event type in the trail with its outcome split and the window it spans.
     *
     * Deliberately a read rather than a hardcoded taxonomy. Splitting configuration changes from
     * user activity the way Firebase does needs the real list of event types this deployment emits,
     * and guessing that list would produce a filter that silently drops rows.
     */
    EventTypes("Event types"),

    /**
     * Per-app provider client rows — issuer, client id, platform, enabled. Never `client_secret_ref`.
     *
     * The verifier picks the first provider matching the kind and takes its accepted audience from
     * global configuration, so these rows are not consulted at verification time. Reading them is
     * how that gap stops being a sentence in a review and becomes a comparison on screen.
     */
    ProviderClients("Provider clients"),
}

/** Metadata projections of heimdall.sql. Credential material and arbitrary JSON never enter a result. */
object AuthDirectoryQueries {

    /** Rows returned per page. A page that comes back full means more may exist, not that none do. */
    const val PageSize: Int = 200

    /** Days of inactivity after which access counts as unused. Reported alongside every finding. */
    const val UnusedWindowDays: Int = 90

    /**
     * Free text is interpolated, so it is restricted rather than escaped: anything outside this set
     * is rejected before a statement is built. Quote, backslash, semicolon and comment markers are
     * all outside it, so a term cannot leave its literal.
     *
     * Underscore is deliberately inside it — `TOKEN_MINT` and `primary_email` are things people
     * search for — which means it arrives as a live LIKE wildcard and has to be escaped rather than
     * banned. `%` stays out, so the only wildcard the query has is the one it added itself.
     */
    private val Searchable = Regex("[A-Za-z0-9@._+:\\- ]{1,120}")

    fun read(
        directory: AuthDirectory,
        appId: String = "",
        principalId: String = "",
        page: Int = 0,
        search: String = "",
    ): String {
        require(page in 0..10000) { "Page is out of range" }
        fun uuid(value: String): String = UUID.fromString(value.trim()).toString()
        val app = appId.takeIf(String::isNotBlank)?.let(::uuid)
        val principal = principalId.takeIf(String::isNotBlank)?.let(::uuid)
        val term = search.trim().takeIf(String::isNotEmpty)?.also {
            require(Searchable.matches(it)) {
                "Search accepts letters, digits and @ . _ + : - only."
            }
        }

        /** ` AND (a ILIKE '%term%' OR b ILIKE '%term%')`, or nothing when no term was given. */
        val pattern = term?.replace("_", "\\_")
        fun match(vararg columns: String): String =
            if (pattern == null || columns.isEmpty()) ""
            else columns.joinToString(" OR ", prefix = " AND (", postfix = ")") {
                "$it ILIKE '%$pattern%' ESCAPE '\\'"
            }

        val projection = when (directory) {
            AuthDirectory.Principals -> """
                SELECT p.id AS principal_id, p.kind, p.status, i.id AS identity_id, i.primary_email,
                    p.created_at, p.updated_at
                FROM heimdall.principal p LEFT JOIN heimdall.identity i ON i.id = p.identity_id
                WHERE 1=1
            """.trimIndent() + (app?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')" } ?: "") +
                (principal?.let { " AND p.id = '$it'" } ?: "") +
                match("i.primary_email", "CAST(p.id AS VARCHAR)", "CAST(i.id AS VARCHAR)", "p.kind", "p.status") +
                " ORDER BY p.created_at DESC, p.id"
            AuthDirectory.Memberships -> scoped("""
                SELECT m.id, m.principal_id, m.app_id, a.name AS app, m.tenant_id, m.context_id, m.status, m.created_at
                FROM heimdall.membership m JOIN heimdall.app a ON a.id = m.app_id WHERE 1=1
            """, "m", app, principal) + match("a.name", "CAST(m.principal_id AS VARCHAR)", "m.status") +
                " ORDER BY m.created_at DESC, m.id"
            AuthDirectory.Sessions -> scoped("""
                SELECT s.id AS session_id, s.principal_id, s.app_id, s.tenant_id, s.context_id,
                    s.created_at, s.expires_at, CASE WHEN s.expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'active' END AS status
                FROM heimdall.session s WHERE 1=1
            """, "s", app, principal) + match("CAST(s.id AS VARCHAR)", "CAST(s.principal_id AS VARCHAR)") +
                " ORDER BY s.created_at DESC, s.id"
            AuthDirectory.RefreshTokens -> scoped("""
                SELECT r.id AS token_id, 'refresh' AS token_type, r.principal_id, r.session_id, r.family_id,
                    r.previous_token_id, s.app_id, r.created_at, r.expires_at, r.used_at, r.rotated_at, r.revoked_at,
                    CASE WHEN r.revoked_at IS NOT NULL THEN 'revoked' WHEN r.expires_at <= CURRENT_TIMESTAMP THEN 'expired'
                         WHEN r.used_at IS NOT NULL THEN 'used' ELSE 'active' END AS status
                FROM heimdall.refresh_token r JOIN heimdall.session s ON s.id = r.session_id WHERE 1=1
            """, "s", app, principal) +
                match("CAST(r.id AS VARCHAR)", "CAST(r.principal_id AS VARCHAR)", "CAST(r.family_id AS VARCHAR)") +
                " ORDER BY r.created_at DESC, r.id"
            AuthDirectory.PersonalTokens -> scoped("""
                SELECT t.id AS token_id, 'PAT' AS token_type, t.name, t.principal_id, t.app_id, t.context_id,
                    t.scopes, t.allowed_audiences, t.created_at, t.expires_at, t.last_used_at, t.revoked_at,
                    CASE WHEN t.revoked_at IS NOT NULL THEN 'revoked' WHEN t.expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'active' END AS status
                FROM heimdall.personal_access_token t WHERE 1=1
            """, "t", app, principal) +
                match("t.name", "CAST(t.id AS VARCHAR)", "CAST(t.principal_id AS VARCHAR)", "t.scopes") +
                " ORDER BY t.created_at DESC, t.id"
            AuthDirectory.ServiceAccounts -> scoped("""
                SELECT s.id, 'service' AS credential_type, s.name, s.principal_id, s.app_id,
                    s.client_id, s.auth_method, s.scopes, s.allowed_audiences, s.status, s.created_at, s.updated_at
                FROM heimdall.service_account s WHERE 1=1
            """, "s", app, principal) +
                match("s.name", "s.client_id", "CAST(s.principal_id AS VARCHAR)", "s.scopes") +
                " ORDER BY s.created_at DESC, s.id"
            AuthDirectory.Grants -> """
                SELECT g.id AS grant_id, g.principal_id, r.app_id, r.name AS role, g.context_id,
                    p.name AS permission, g.created_at
                FROM heimdall.principal_role g JOIN heimdall.role r ON r.id = g.role_id
                LEFT JOIN heimdall.role_permissions rp ON rp.role_id = r.id
                LEFT JOIN heimdall.permission p ON p.id = rp.permission_id WHERE 1=1
            """.trimIndent() + (app?.let { " AND r.app_id = '$it'" } ?: "") +
                (principal?.let { " AND g.principal_id = '$it'" } ?: "") +
                match("r.name", "p.name", "CAST(g.principal_id AS VARCHAR)") +
                " ORDER BY g.created_at DESC, g.id, p.id"
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
                match(
                    "i.primary_email", "CAST(p.id AS VARCHAR)", "CAST(i.id AS VARCHAR)", "p.kind", "p.status",
                    "(SELECT MIN(sa.name) FROM heimdall.service_account sa WHERE sa.principal_id = p.id)",
                    "(SELECT MIN(sa.client_id) FROM heimdall.service_account sa WHERE sa.principal_id = p.id)",
                    "(SELECT MIN(pa.subject) FROM heimdall.provider_account pa WHERE pa.identity_id = p.identity_id)",
                ) +
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
                match("r.name", "a.name") +
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
                match("pm.name", "a.name") +
                " ORDER BY a.name, pm.name, pm.id"
            AuthDirectory.SigningKeys -> """
                SELECT k.kid, k.alg, k.status,
                    CASE
                        WHEN k.expires_at IS NOT NULL AND k.expires_at <= CURRENT_TIMESTAMP THEN 'revoked'
                        WHEN k.status = 'current' THEN 'active'
                        WHEN k.rotated_at IS NOT NULL THEN 'previously used'
                        ELSE 'standby'
                    END AS state,
                    k.rotated_at, k.expires_at, k.created_at,
                    CASE WHEN k.private_key_ref IS NULL THEN 'none' ELSE 'configured' END AS private_key
                FROM heimdall.signing_key k WHERE 1=1
            """.trimIndent() + match("k.kid", "k.alg", "k.status") +
                " ORDER BY k.created_at DESC, k.kid"
            AuthDirectory.Composition -> composition(app)
            AuthDirectory.StaleRefresh -> """
                SELECT p.id AS principal_id, p.kind, p.status AS principal_status,
                    COALESCE(i.status, 'none') AS identity_status, i.primary_email,
                    CAST(COUNT(r.id) AS BIGINT) AS live_refresh_tokens,
                    MAX(r.expires_at) AS access_retained_until,
                    MAX(s.created_at) AS last_session
                FROM heimdall.principal p
                JOIN heimdall.refresh_token r ON r.principal_id = p.id
                JOIN heimdall.session s ON s.id = r.session_id
                LEFT JOIN heimdall.identity i ON i.id = p.identity_id
                WHERE r.revoked_at IS NULL AND r.used_at IS NULL AND r.expires_at > CURRENT_TIMESTAMP
                  AND (
                    p.status <> 'ACTIVE' OR i.status <> 'ACTIVE'
                    OR EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.status <> 'ACTIVE')
                  )
            """.trimIndent() + (app?.let { " AND s.app_id = '$it'" } ?: "") +
                (principal?.let { " AND p.id = '$it'" } ?: "") +
                " GROUP BY p.id, p.kind, p.status, i.status, i.primary_email" +
                " ORDER BY access_retained_until DESC, p.id"
            AuthDirectory.OverScopedTokens -> """
                SELECT t.id AS token_id, t.name, t.principal_id, p.kind AS principal_kind,
                    p.status AS principal_status, t.app_id, t.scopes, t.allowed_audiences,
                    CASE WHEN t.scopes LIKE '%*%' THEN 'wildcard scope' ELSE 'auth-administrative scope' END AS finding,
                    t.created_at, t.expires_at, t.last_used_at
                FROM heimdall.personal_access_token t
                LEFT JOIN heimdall.principal p ON p.id = t.principal_id
                WHERE t.revoked_at IS NULL
                  AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)
                  AND (t.scopes LIKE '%*%' OR t.scopes LIKE '%auth:%')
            """.trimIndent() + (app?.let { " AND t.app_id = '$it'" } ?: "") +
                (principal?.let { " AND t.principal_id = '$it'" } ?: "") +
                match("t.name", "t.scopes", "CAST(t.principal_id AS VARCHAR)") +
                " ORDER BY t.created_at DESC, t.id"
            AuthDirectory.UnusedAccess -> unusedAccess(app)
            AuthDirectory.EventTypes -> """
                SELECT e.event_type, e.outcome, CAST(COUNT(*) AS BIGINT) AS total,
                    MIN(e.created_at) AS first_seen, MAX(e.created_at) AS last_seen
                FROM heimdall.auth_audit_event e WHERE 1=1
            """.trimIndent() + (app?.let { " AND e.app_id = '$it'" } ?: "") +
                (principal?.let { " AND (e.subject_principal_id = '$it' OR e.actor_principal_id = '$it')" } ?: "") +
                match("e.event_type", "e.outcome") +
                " GROUP BY e.event_type, e.outcome ORDER BY total DESC, e.event_type"
            AuthDirectory.ProviderClients -> """
                SELECT c.app_id, a.name AS app, c.provider, c.platform, c.client_id, c.issuer,
                    c.apple_team_id, c.bundle_id, c.enabled,
                    CASE WHEN c.client_secret_ref IS NULL THEN 'none' ELSE 'configured' END AS client_secret
                FROM heimdall.auth_provider_client c JOIN heimdall.app a ON a.id = c.app_id
                WHERE 1=1
            """.trimIndent() + (app?.let { " AND c.app_id = '$it'" } ?: "") +
                match("c.provider", "c.platform", "c.client_id", "c.issuer", "a.name") +
                " ORDER BY a.name, c.provider, c.platform"
            AuthDirectory.Audit, AuthDirectory.AccessIssuance, AuthDirectory.AuthFailures -> """
                SELECT e.id, e.created_at, e.event_type, e.outcome, e.reason, e.actor_principal_id,
                    e.subject_principal_id, e.app_id, e.tenant_id, e.context_id, e.session_id,
                    e.credential_type, e.grant_type, e.token_id, e.audience, e.scopes,
                    e.request_id, e.ip_address, e.user_agent
                FROM heimdall.auth_audit_event e WHERE 1=1
            """.trimIndent() + (app?.let { " AND e.app_id = '$it'" } ?: "") +
                (principal?.let { " AND (e.subject_principal_id = '$it' OR e.actor_principal_id = '$it')" } ?: "") +
                (if (directory == AuthDirectory.AccessIssuance) " AND e.event_type IN ('TOKEN_MINT', 'TOKEN_EXCHANGE')" else "") +
                // Outcome is a free varchar, so match on what a success looks like rather than on a
                // value this code does not own. Anything that is not a success is worth reading.
                (if (directory == AuthDirectory.AuthFailures) " AND e.outcome NOT ILIKE 'succ%'" else "") +
                match(
                    "e.event_type", "e.outcome", "e.reason", "e.request_id", "e.audience",
                    "CAST(e.actor_principal_id AS VARCHAR)", "CAST(e.subject_principal_id AS VARCHAR)",
                ) +
                " ORDER BY e.created_at DESC, e.id"
        }
        return "$projection LIMIT $PageSize OFFSET ${page * PageSize}"
    }

    /**
     * Five buckets in one read, because the alternative is five round trips for four numbers each.
     * The app filter reaches the buckets it can: principals through membership, audit through its
     * own column. Identity and provider rows are global and their labels say so.
     */
    private fun composition(app: String?): String {
        val principalScope = app?.let {
            " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')"
        } ?: ""
        val auditScope = app?.let { " AND e.app_id = '$it'" } ?: ""
        return """
            SELECT 'principal kind' AS bucket, p.kind AS label, CAST(COUNT(*) AS BIGINT) AS total
            FROM heimdall.principal p WHERE 1=1$principalScope GROUP BY p.kind
            UNION ALL
            SELECT 'principal status', p.status, CAST(COUNT(*) AS BIGINT)
            FROM heimdall.principal p WHERE 1=1$principalScope GROUP BY p.status
            UNION ALL
            SELECT 'identity status (all apps)', i.status, CAST(COUNT(*) AS BIGINT)
            FROM heimdall.identity i GROUP BY i.status
            UNION ALL
            SELECT 'provider (all apps)', pa.provider, CAST(COUNT(*) AS BIGINT)
            FROM heimdall.provider_account pa GROUP BY pa.provider
            UNION ALL
            SELECT 'audit outcome (7d)', e.outcome, CAST(COUNT(*) AS BIGINT)
            FROM heimdall.auth_audit_event e
            WHERE e.created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY$auditScope
            GROUP BY e.outcome
            ORDER BY 1, 3 DESC, 2
        """.trimIndent()
    }

    /**
     * Access that exists and is not exercised, in three shapes that share one row form so they can
     * be read as one list. Every row carries the evidence its finding rests on; a dormant principal
     * is a claim about the audit trail, not about the principal.
     */
    private fun unusedAccess(app: String?): String {
        val roleScope = app?.let { " AND r.app_id = '$it'" } ?: ""
        val tokenScope = app?.let { " AND t.app_id = '$it'" } ?: ""
        val principalScope = app?.let {
            " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')"
        } ?: ""
        return """
            SELECT 'role held by nobody' AS finding, r.name AS subject,
                CAST(r.id AS VARCHAR) AS subject_id, a.name AS app,
                CAST(0 AS BIGINT) AS grants, CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS last_used,
                'no principal_role row' AS evidence, r.created_at
            FROM heimdall.role r JOIN heimdall.app a ON a.id = r.app_id
            WHERE NOT EXISTS (SELECT 1 FROM heimdall.principal_role pr WHERE pr.role_id = r.id)$roleScope
            UNION ALL
            SELECT 'token never presented', t.name, CAST(t.id AS VARCHAR), '',
                CAST(1 AS BIGINT), t.last_used_at, 'last_used_at is null', t.created_at
            FROM heimdall.personal_access_token t
            WHERE t.last_used_at IS NULL AND t.revoked_at IS NULL
              AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)$tokenScope
            UNION ALL
            SELECT 'principal dormant with grants',
                COALESCE(i.primary_email, CAST(p.id AS VARCHAR)), CAST(p.id AS VARCHAR), '',
                CAST((SELECT COUNT(*) FROM heimdall.principal_role pr WHERE pr.principal_id = p.id) AS BIGINT),
                CAST(NULL AS TIMESTAMP WITH TIME ZONE),
                'no audit event in $UnusedWindowDays days', p.created_at
            FROM heimdall.principal p LEFT JOIN heimdall.identity i ON i.id = p.identity_id
            WHERE EXISTS (SELECT 1 FROM heimdall.principal_role pr WHERE pr.principal_id = p.id)
              AND NOT EXISTS (
                SELECT 1 FROM heimdall.auth_audit_event e
                WHERE (e.actor_principal_id = p.id OR e.subject_principal_id = p.id)
                  AND e.created_at > CURRENT_TIMESTAMP - INTERVAL '$UnusedWindowDays' DAY
              )$principalScope
            ORDER BY 1, 8 DESC
        """.trimIndent()
    }

    private fun scoped(sql: String, alias: String, app: String?, principal: String?): String = sql.trimIndent() +
        (app?.let { " AND $alias.app_id = '$it'" } ?: "") +
        (principal?.let { " AND $alias.principal_id = '$it'" } ?: "")
}
