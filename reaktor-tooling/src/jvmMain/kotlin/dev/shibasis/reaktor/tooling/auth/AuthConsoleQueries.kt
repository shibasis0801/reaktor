package dev.shibasis.reaktor.tooling.auth

import java.util.UUID

data class AuditFilter(
    val appId: String = "",
    val principalId: String = "",
    val sessionId: String = "",
    val eventType: String = "",
    val outcome: String = "",
    val reason: String = "",
    val search: String = "",
)

enum class SessionState { Active, Expired, Revoked }

object AuthConsoleQueries {
    private const val Fixture = "CAST(s.data AS VARCHAR) LIKE '%bestbudsDevAuthFixture%'"
    const val PageSize: Int = 200
    const val SeriesDays: Int = 30
    const val SeriesRows: Int = 500

    fun pulse(appId: String = ""): String {
        val app = uuidOrNull(appId)
        val principals = app?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')" } ?: ""
        val sessions = app?.let { " AND s.app_id = '$it'" } ?: ""
        val events = app?.let { " AND e.app_id = '$it'" } ?: ""
        fun within(days: Int) = "e.created_at > CURRENT_TIMESTAMP - INTERVAL '$days' DAY"
        fun principalsWhere(condition: String) = "(SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.principal p WHERE $condition$principals)"
        fun active(days: Int) = "(SELECT CAST(COUNT(DISTINCT COALESCE(e.subject_principal_id, e.actor_principal_id)) AS BIGINT) " +
            "FROM heimdall.auth_audit_event e WHERE e.outcome ILIKE 'succ%' AND ${within(days)}$events)"
        fun events(days: Int, condition: String) = "(SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.auth_audit_event e WHERE ${within(days)} AND $condition$events)"
        return """
            SELECT
                ${principalsWhere("p.kind = 'USER'")} AS users,
                ${principalsWhere("p.kind = 'SERVICE'")} AS services,
                ${principalsWhere("p.kind = 'AGENT'")} AS agents,
                ${principalsWhere("p.status <> 'ACTIVE'")} AS inactive,
                ${principalsWhere("p.created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY")} AS new_7d,
                ${principalsWhere("p.created_at > CURRENT_TIMESTAMP - INTERVAL '30' DAY")} AS new_30d,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.session s WHERE s.expires_at > CURRENT_TIMESTAMP$sessions) AS active_sessions,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.session s WHERE s.expires_at > CURRENT_TIMESTAMP AND $Fixture$sessions) AS fixture_sessions,
                (SELECT CAST(COUNT(DISTINCT s.principal_id) AS BIGINT) FROM heimdall.session s WHERE s.expires_at > CURRENT_TIMESTAMP$sessions) AS signed_in,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.refresh_token r JOIN heimdall.session s ON s.id = r.session_id
                    WHERE r.revoked_at IS NULL AND r.used_at IS NULL AND r.expires_at > CURRENT_TIMESTAMP$sessions) AS live_refresh,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.personal_access_token t
                    WHERE t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > CURRENT_TIMESTAMP)${app?.let { " AND t.app_id = '$it'" } ?: ""}) AS live_pats,
                (SELECT CAST(COUNT(*) AS BIGINT) FROM heimdall.personal_access_token t
                    WHERE t.revoked_at IS NULL AND t.expires_at IS NULL${app?.let { " AND t.app_id = '$it'" } ?: ""}) AS endless_pats,
                ${active(1)} AS active_1d,
                ${active(7)} AS active_7d,
                ${active(30)} AS active_30d,
                ${events(1, "e.event_type = 'TOKEN_MINT' AND e.credential_type = 'external_login'")} AS sign_ins_1d,
                ${events(7, "e.event_type = 'TOKEN_MINT' AND e.credential_type = 'external_login'")} AS sign_ins_7d,
                ${events(1, "e.outcome NOT ILIKE 'succ%'")} AS failures_1d,
                ${events(7, "e.outcome NOT ILIKE 'succ%'")} AS failures_7d,
                ${events(1, "1=1")} AS events_1d,
                ${events(7, "1=1")} AS events_7d,
                (SELECT MAX(e.created_at) FROM heimdall.auth_audit_event e WHERE 1=1$events) AS last_event_at
        """.trimIndent()
    }

    fun activity(appId: String = "", principalId: String = "", days: Int = SeriesDays): String {
        require(days in 1..365) { "Activity spans 1 to 365 days" }
        val app = uuidOrNull(appId)
        val principal = uuidOrNull(principalId)
        val window = "CURRENT_TIMESTAMP - INTERVAL '$days' DAY"
        val events = (app?.let { " AND e.app_id = '$it'" } ?: "") +
            (principal?.let { " AND (e.subject_principal_id = '$it' OR e.actor_principal_id = '$it')" } ?: "")
        val sessions = (app?.let { " AND s.app_id = '$it'" } ?: "") + (principal?.let { " AND s.principal_id = '$it'" } ?: "")
        val principals = (app?.let { " AND EXISTS (SELECT 1 FROM heimdall.membership m WHERE m.principal_id = p.id AND m.app_id = '$it')" } ?: "") +
            (principal?.let { " AND p.id = '$it'" } ?: "")
        return """
            SELECT 'event' AS series, CAST(e.created_at AS DATE) AS event_day, e.event_type, e.outcome, CAST(COUNT(*) AS BIGINT) AS total
            FROM heimdall.auth_audit_event e WHERE e.created_at > $window$events
            GROUP BY CAST(e.created_at AS DATE), e.event_type, e.outcome
            UNION ALL
            SELECT 'signin', CAST(e.created_at AS DATE), '', '', CAST(COUNT(*) AS BIGINT)
            FROM heimdall.auth_audit_event e WHERE e.event_type = 'TOKEN_MINT' AND e.credential_type = 'external_login'
                AND e.outcome ILIKE 'succ%' AND e.created_at > $window$events
            GROUP BY CAST(e.created_at AS DATE)
            UNION ALL
            SELECT 'machine', CAST(e.created_at AS DATE), '', '', CAST(COUNT(*) AS BIGINT)
            FROM heimdall.auth_audit_event e WHERE e.credential_type = 'client_credentials' AND e.created_at > $window$events
            GROUP BY CAST(e.created_at AS DATE)
            UNION ALL
            SELECT 'active', CAST(e.created_at AS DATE), '', '', CAST(COUNT(DISTINCT COALESCE(e.subject_principal_id, e.actor_principal_id)) AS BIGINT)
            FROM heimdall.auth_audit_event e WHERE e.outcome ILIKE 'succ%' AND COALESCE(e.credential_type, '') <> 'client_credentials' AND e.created_at > $window$events
            GROUP BY CAST(e.created_at AS DATE)
            UNION ALL
            SELECT 'session', CAST(s.created_at AS DATE), CASE WHEN $Fixture THEN 'fixture' ELSE 'real' END, '', CAST(COUNT(*) AS BIGINT)
            FROM heimdall.session s WHERE s.created_at > $window$sessions
            GROUP BY CAST(s.created_at AS DATE), CASE WHEN $Fixture THEN 'fixture' ELSE 'real' END
            UNION ALL
            SELECT 'signup', CAST(p.created_at AS DATE), p.kind, '', CAST(COUNT(*) AS BIGINT)
            FROM heimdall.principal p WHERE p.created_at > $window$principals
            GROUP BY CAST(p.created_at AS DATE), p.kind
            ORDER BY 1, 2, 3, 4
            LIMIT $SeriesRows
        """.trimIndent()
    }

    fun failureReasons(appId: String = "", days: Int = SeriesDays): String {
        require(days in 1..365) { "Failure reasons span 1 to 365 days" }
        val app = uuidOrNull(appId)
        return """
            SELECT e.event_type, COALESCE(e.reason, '') AS reason, COALESCE(e.credential_type, '') AS credential_type,
                COALESCE(e.grant_type, '') AS grant_type, CAST(COUNT(*) AS BIGINT) AS total,
                CAST(SUM(CASE WHEN e.created_at > CURRENT_TIMESTAMP - INTERVAL '1' DAY THEN 1 ELSE 0 END) AS BIGINT) AS last_day,
                CAST(COUNT(DISTINCT e.ip_address) AS BIGINT) AS addresses,
                CAST(COUNT(DISTINCT COALESCE(e.subject_principal_id, e.actor_principal_id)) AS BIGINT) AS principals,
                CAST(COUNT(DISTINCT e.app_id) AS BIGINT) AS apps,
                MIN(e.created_at) AS first_seen, MAX(e.created_at) AS last_seen
            FROM heimdall.auth_audit_event e
            WHERE e.outcome NOT ILIKE 'succ%' AND e.created_at > CURRENT_TIMESTAMP - INTERVAL '$days' DAY${app?.let { " AND e.app_id = '$it'" } ?: ""}
            GROUP BY e.event_type, COALESCE(e.reason, ''), COALESCE(e.credential_type, ''), COALESCE(e.grant_type, '')
            ORDER BY total DESC, 1, 2
            LIMIT $PageSize
        """.trimIndent()
    }

    fun providers(): String = """
        SELECT pa.provider, COALESCE(pa.issuer, '') AS issuer, CAST(COUNT(*) AS BIGINT) AS accounts,
            CAST(COUNT(DISTINCT pa.identity_id) AS BIGINT) AS identities,
            CAST(SUM(CASE WHEN pa.email_verified = TRUE THEN 1 ELSE 0 END) AS BIGINT) AS verified,
            CAST(SUM(CASE WHEN pa.created_at > CURRENT_TIMESTAMP - INTERVAL '30' DAY THEN 1 ELSE 0 END) AS BIGINT) AS linked_30d,
            MIN(pa.created_at) AS first_linked, MAX(pa.created_at) AS last_linked
        FROM heimdall.provider_account pa
        GROUP BY pa.provider, COALESCE(pa.issuer, '')
        ORDER BY accounts DESC, 1
    """.trimIndent()

    fun linkedAccounts(principalId: String): String {
        val principal = requireNotNull(uuidOrNull(principalId)) { "Linked accounts need a principal" }
        return """
            SELECT pa.provider, COALESCE(pa.issuer, '') AS issuer, pa.email, pa.email_verified, pa.created_at, pa.updated_at
            FROM heimdall.provider_account pa JOIN heimdall.principal p ON p.identity_id = pa.identity_id
            WHERE p.id = '$principal'
            ORDER BY pa.created_at, pa.provider
        """.trimIndent()
    }

    fun sessions(
        appId: String = "",
        principalId: String = "",
        state: SessionState? = null,
        search: String = "",
        page: Int = 0,
        fixtures: Boolean? = null,
    ): String {
        require(page in 0..10_000) { "Page is out of range" }
        val app = uuidOrNull(appId)
        val principal = uuidOrNull(principalId)
        val status = """
            CASE WHEN rt.revoked > 0 AND rt.live = 0 THEN 'revoked'
                 WHEN s.expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'active' END
        """.trimIndent()
        return """
            SELECT s.id AS session_id, s.principal_id, p.kind, a.name AS app, s.app_id,
                COALESCE(i.primary_email, (SELECT MIN(sa.name) FROM heimdall.service_account sa WHERE sa.principal_id = s.principal_id)) AS name,
                s.created_at, s.expires_at, $status AS status, CASE WHEN $Fixture THEN 'fixture' ELSE 'real' END AS origin,
                COALESCE(rt.issued, 0) AS refreshes, COALESCE(rt.live, 0) AS live_refresh, COALESCE(rt.revoked, 0) AS revoked_refresh,
                rt.last_refresh_at, ev.last_event_at, ev.user_agent, ev.ip_address, COALESCE(ev.failures, 0) AS failures
            FROM heimdall.session s
            JOIN heimdall.app a ON a.id = s.app_id
            LEFT JOIN heimdall.principal p ON p.id = s.principal_id
            LEFT JOIN heimdall.identity i ON i.id = p.identity_id
            LEFT JOIN (
                SELECT r.session_id, CAST(COUNT(*) AS BIGINT) AS issued,
                    CAST(SUM(CASE WHEN r.revoked_at IS NULL AND r.used_at IS NULL AND r.expires_at > CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS BIGINT) AS live,
                    CAST(SUM(CASE WHEN r.revoked_at IS NOT NULL THEN 1 ELSE 0 END) AS BIGINT) AS revoked,
                    MAX(COALESCE(r.used_at, r.created_at)) AS last_refresh_at
                FROM heimdall.refresh_token r GROUP BY r.session_id
            ) rt ON rt.session_id = s.id
            LEFT JOIN (
                SELECT e.session_id, MAX(e.created_at) AS last_event_at, MAX(e.user_agent) AS user_agent, MAX(e.ip_address) AS ip_address,
                    CAST(SUM(CASE WHEN e.outcome NOT ILIKE 'succ%' THEN 1 ELSE 0 END) AS BIGINT) AS failures
                FROM heimdall.auth_audit_event e WHERE e.session_id IS NOT NULL GROUP BY e.session_id
            ) ev ON ev.session_id = s.id
            WHERE 1=1
        """.trimIndent() +
            (app?.let { " AND s.app_id = '$it'" } ?: "") +
            (principal?.let { " AND s.principal_id = '$it'" } ?: "") +
            when (fixtures) {
                null -> ""
                true -> " AND $Fixture"
                false -> " AND NOT ($Fixture)"
            } +
            when (state) {
                null -> ""
                SessionState.Active -> " AND s.expires_at > CURRENT_TIMESTAMP AND NOT (COALESCE(rt.revoked, 0) > 0 AND COALESCE(rt.live, 0) = 0)"
                SessionState.Expired -> " AND s.expires_at <= CURRENT_TIMESTAMP"
                SessionState.Revoked -> " AND COALESCE(rt.revoked, 0) > 0 AND COALESCE(rt.live, 0) = 0"
            } +
            searchConditions(search, listOf("CAST(s.id AS VARCHAR)", "CAST(s.principal_id AS VARCHAR)", "i.primary_email", "a.name", "ev.user_agent")) { key, value, regex ->
                when (key) {
                    "name", "email" -> searchMatch(value, regex, "i.primary_email")
                    "app" -> searchMatch(value, regex, "a.name")
                    "kind" -> searchMatch(value, regex, "p.kind")
                    "device" -> searchMatch(value, regex, "ev.user_agent")
                    "ip" -> searchMatch(value, regex, "ev.ip_address")
                    "session" -> searchMatch(value, regex, "CAST(s.id AS VARCHAR)")
                    "user", "principal" -> searchMatch(value, regex, "CAST(s.principal_id AS VARCHAR)")
                    "age" -> searchAge(value, "s.created_at")
                    "is" -> when (value.lowercase()) {
                        "active" -> "s.expires_at > CURRENT_TIMESTAMP AND NOT (COALESCE(rt.revoked, 0) > 0 AND COALESCE(rt.live, 0) = 0)"
                        "expired" -> "s.expires_at <= CURRENT_TIMESTAMP"
                        "revoked" -> "COALESCE(rt.revoked, 0) > 0 AND COALESCE(rt.live, 0) = 0"
                        "fixture" -> Fixture
                        "failed" -> "COALESCE(ev.failures, 0) > 0"
                        else -> null
                    }
                    else -> null
                }
            } +
            " ORDER BY s.created_at DESC, s.id LIMIT $PageSize OFFSET ${page * PageSize}"
    }

    fun refreshChain(sessionId: String): String {
        val session = requireNotNull(uuidOrNull(sessionId)) { "A refresh chain needs a session" }
        return """
            SELECT r.id AS token_id, r.family_id, r.previous_token_id, r.created_at, r.expires_at, r.used_at, r.rotated_at, r.revoked_at,
                CASE WHEN r.revoked_at IS NOT NULL THEN 'revoked' WHEN r.used_at IS NOT NULL THEN 'rotated'
                     WHEN r.expires_at <= CURRENT_TIMESTAMP THEN 'expired' ELSE 'live' END AS status
            FROM heimdall.refresh_token r
            WHERE r.session_id = '$session'
            ORDER BY r.created_at, r.id
            LIMIT $PageSize
        """.trimIndent()
    }

    fun events(filter: AuditFilter, page: Int = 0): String {
        require(page in 0..10_000) { "Page is out of range" }
        val app = uuidOrNull(filter.appId)
        val principal = uuidOrNull(filter.principalId)
        val session = uuidOrNull(filter.sessionId)
        val eventType = filterCode(filter.eventType)
        val outcome = filterCode(filter.outcome)
        val reason = filterCode(filter.reason)
        return """
            SELECT e.id, e.created_at, e.event_type, e.outcome, e.reason, e.credential_type, e.grant_type,
                e.actor_principal_id, e.subject_principal_id, a.name AS app, e.app_id, e.tenant_id, e.session_id,
                e.token_id, e.audience, e.request_id, e.ip_address, e.user_agent
            FROM heimdall.auth_audit_event e LEFT JOIN heimdall.app a ON a.id = e.app_id
            WHERE 1=1
        """.trimIndent() +
            (app?.let { " AND e.app_id = '$it'" } ?: "") +
            (principal?.let { " AND (e.subject_principal_id = '$it' OR e.actor_principal_id = '$it')" } ?: "") +
            (session?.let { " AND e.session_id = '$it'" } ?: "") +
            (eventType?.let { " AND e.event_type = '$it'" } ?: "") +
            when (outcome?.uppercase()) {
                null -> ""
                "SUCCESS" -> " AND e.outcome ILIKE 'succ%'"
                "FAILURE" -> " AND e.outcome NOT ILIKE 'succ%'"
                else -> " AND e.outcome = '$outcome'"
            } +
            (reason?.let { " AND e.reason = '$it'" } ?: "") +
            searchConditions(filter.search, listOf("e.event_type", "e.outcome", "e.reason", "e.request_id", "e.audience", "e.credential_type", "e.grant_type",
                "e.user_agent", "e.ip_address", "CAST(e.actor_principal_id AS VARCHAR)", "CAST(e.subject_principal_id AS VARCHAR)", "CAST(e.session_id AS VARCHAR)", "a.name")) { key, value, regex ->
                when (key) {
                    "reason" -> searchMatch(value, regex, "e.reason")
                    "request" -> searchMatch(value, regex, "e.request_id")
                    "audience" -> searchMatch(value, regex, "e.audience")
                    "device" -> searchMatch(value, regex, "e.user_agent")
                    "ip" -> searchMatch(value, regex, "e.ip_address")
                    "app" -> searchMatch(value, regex, "a.name")
                    "type" -> searchMatch(value, regex, "e.event_type")
                    "session" -> searchMatch(value, regex, "CAST(e.session_id AS VARCHAR)")
                    "user", "principal" -> searchMatch(value, regex, "CAST(e.actor_principal_id AS VARCHAR)", "CAST(e.subject_principal_id AS VARCHAR)")
                    "age" -> searchAge(value, "e.created_at")
                    "is" -> when (value.lowercase()) {
                        "failed", "failure", "error" -> "e.outcome NOT ILIKE 'succ%'"
                        "succeeded", "success" -> "e.outcome ILIKE 'succ%'"
                        else -> null
                    }
                    else -> null
                }
            } +
            " ORDER BY e.created_at DESC, e.id LIMIT $PageSize OFFSET ${page * PageSize}"
    }

    fun exposure(): String = """
        SELECT n.nspname AS schema_name, c.relname AS table_name, c.relrowsecurity AS rls_enabled, c.relforcerowsecurity AS rls_forced,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM pg_catalog.pg_policy pol WHERE pol.polrelid = c.oid) AS policies,
            (SELECT CAST(COUNT(*) AS BIGINT) FROM pg_catalog.pg_policy pol WHERE pol.polrelid = c.oid AND pol.polpermissive
                AND (0 = ANY(pol.polroles) OR EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.oid = ANY(pol.polroles) AND r.rolname IN ('anon', 'authenticated')))
                AND (pol.polqual IS NULL OR pg_catalog.pg_get_expr(pol.polqual, pol.polrelid) = 'true')
                AND (pol.polwithcheck IS NULL OR pg_catalog.pg_get_expr(pol.polwithcheck, pol.polrelid) = 'true')) AS open_policies,
            COALESCE((SELECT STRING_AGG(DISTINCT x.privilege_type, ',') FROM aclexplode(c.relacl) x
                JOIN pg_catalog.pg_roles r ON r.oid = x.grantee WHERE r.rolname = 'anon'), '') AS anon_privileges,
            COALESCE((SELECT STRING_AGG(DISTINCT x.privilege_type, ',') FROM aclexplode(c.relacl) x
                JOIN pg_catalog.pg_roles r ON r.oid = x.grantee WHERE r.rolname = 'authenticated'), '') AS authenticated_privileges,
            COALESCE((SELECT STRING_AGG(DISTINCT x.privilege_type, ',') FROM aclexplode(c.relacl) x WHERE x.grantee = 0), '') AS public_privileges,
            COALESCE((SELECT STRING_AGG(DISTINCT r.rolname, ',') FROM aclexplode(n.nspacl) x
                JOIN pg_catalog.pg_roles r ON r.oid = x.grantee
                WHERE x.privilege_type = 'USAGE' AND r.rolname IN ('anon', 'authenticated')), '') AS schema_usage,
            COALESCE((SELECT STRING_AGG(DISTINCT COALESCE(r.rolname, 'PUBLIC'), ',') FROM aclexplode(n.nspacl) x
                LEFT JOIN pg_catalog.pg_roles r ON r.oid = x.grantee
                WHERE x.privilege_type = 'CREATE' AND (x.grantee = 0 OR r.rolname IN ('anon', 'authenticated'))), '') AS schema_create,
            c.reltuples AS estimated_rows
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r', 'p') AND n.nspname IN ('public', 'heimdall')
        ORDER BY n.nspname, c.relname
        LIMIT 500
    """.trimIndent()

}

internal fun uuidOrNull(value: String): String? = value.trim().takeIf(String::isNotEmpty)?.let { UUID.fromString(it).toString() }

private val Code = Regex("[A-Za-z0-9_:. -]{1,80}")

internal fun filterCode(value: String): String? = value.trim().takeIf(String::isNotEmpty)?.also {
    require(Code.matches(it)) { "Filters accept letters, digits, spaces and _ : . - only." }
}

internal fun searchConditions(raw: String, columns: List<String>, keyed: (String, String, Regex?) -> String?): String {
    require(raw.length <= 120 && raw.none { it == '\u0000' }) { "Search accepts at most 120 characters." }
    return dev.shibasis.reaktor.tooling.query.SearchQuery(raw).mapTerms { key, value, negated, regex ->
        val match = keyed(key.orEmpty(), value, regex) ?: searchMatch(if (key == null) value else "$key:$value", regex.takeIf { key == null }, *columns.toTypedArray())
        " AND ${if (negated) "NOT " else ""}($match)"
    }.joinToString("")
}

internal fun searchMatch(value: String, pattern: Regex?, vararg columns: String): String = columns.joinToString(" OR ") { column ->
    if (pattern != null) "COALESCE($column, '') ~* '${value.replace("'", "''")}'"
    else {
        val literal = value.replace("'", "''").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        "COALESCE($column, '') ILIKE '%$literal%' ESCAPE '\\'"
    }
}

internal fun searchAge(value: String, column: String): String? {
    val duration = Regex("""(\d+(?:\.\d+)?)(ms|s|min|m|h|d)""").matchEntire(value.lowercase()) ?: return null
    val factor = when (duration.groupValues[2]) { "ms" -> .001; "s" -> 1.0; "min", "m" -> 60.0; "h" -> 3600.0; else -> 86400.0 }
    val seconds = duration.groupValues[1].toDoubleOrNull()?.times(factor)?.takeIf { it.isFinite() && it in 0.0..31_622_400.0 } ?: return null
    return "$column >= CURRENT_TIMESTAMP - INTERVAL '$seconds' SECOND"
}
