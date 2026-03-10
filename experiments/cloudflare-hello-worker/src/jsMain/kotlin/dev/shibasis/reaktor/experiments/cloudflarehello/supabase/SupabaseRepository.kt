package dev.shibasis.reaktor.experiments.cloudflarehello.supabase

import dev.shibasis.reaktor.cloudflare.PostgresDatabase

private val countedTables =
    listOf(
        CatalogTarget("heimdall", "users", "table"),
        CatalogTarget("public", "user_profiles", "table"),
        CatalogTarget("public", "onboarding_questions", "table"),
    )

private data class CatalogTarget(
    val schema: String,
    val name: String,
    val kind: String,
)

class SupabaseRepository(
    private val database: PostgresDatabase,
) {
    suspend fun inspectBestBudsSurface(): SupabaseStatusResponse {
        val connection =
            database.firstOrNull<SupabaseConnectionInfo>(
                """
                SELECT
                    current_database()::text AS database,
                    current_user::text AS "currentUser",
                    now()::text AS "serverTime"
                """.trimIndent(),
            ) ?: error("Supabase connection metadata query returned no rows")

        val objects = loadCatalog()
        val counts = buildList {
            countedTables
                .filter { target -> objects.any { it.schema == target.schema && it.name == target.name && it.kind == target.kind } }
                .forEach { target ->
                    countRows(target)?.let(::add)
                }
        }

        return SupabaseStatusResponse(
            connection = connection,
            objects = objects,
            counts = counts,
        )
    }

    private suspend fun loadCatalog(): List<SupabaseCatalogEntry> {
        return database.rows(
            """
            SELECT
                table_schema::text AS schema,
                table_name::text AS name,
                'table'::text AS kind
            FROM information_schema.tables
            WHERE table_schema IN ('heimdall', 'public')
              AND table_name IN ('app', 'users', 'user_profiles', 'onboarding_questions', 'onboarding_responses')
            UNION ALL
            SELECT
                table_schema::text AS schema,
                table_name::text AS name,
                'view'::text AS kind
            FROM information_schema.views
            WHERE table_schema = 'public'
              AND table_name IN ('friends_view', 'group_view', 'campaigns_view', 'users_view')
            ORDER BY schema, kind, name
            """.trimIndent(),
        )
    }

    private suspend fun countRows(target: CatalogTarget): SupabaseTableCount? =
        database.firstOrNull(
            """
            SELECT
                %V::text AS schema,
                %V::text AS name,
                COUNT(*)::int AS count
            FROM %I.%I
            """.trimIndent(),
            target.schema,
            target.name,
            target.schema,
            target.name,
        )
}
