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
            database.firstOrNull(
                """
                SELECT
                    current_database()::text AS database_name,
                    current_user::text AS current_user,
                    now()::text AS server_time
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
            connection =
                SupabaseConnectionInfo(
                    database = connection.string("database_name") ?: error("database_name was missing"),
                    currentUser = connection.string("current_user") ?: error("current_user was missing"),
                    serverTime = connection.string("server_time") ?: error("server_time was missing"),
                ),
            objects = objects,
            counts = counts,
        )
    }

    private suspend fun loadCatalog(): List<SupabaseCatalogEntry> {
        val discovered =
            database.rows(
                """
                SELECT
                    table_schema::text AS schema_name,
                    table_name::text AS object_name,
                    'table'::text AS object_kind
                FROM information_schema.tables
                WHERE table_schema IN ('heimdall', 'public')
                  AND table_name IN ('app', 'users', 'user_profiles', 'onboarding_questions', 'onboarding_responses')
                UNION ALL
                SELECT
                    table_schema::text AS schema_name,
                    table_name::text AS object_name,
                    'view'::text AS object_kind
                FROM information_schema.views
                WHERE table_schema = 'public'
                  AND table_name IN ('friends_view', 'group_view', 'campaigns_view', 'users_view')
                ORDER BY schema_name, object_kind, object_name
                """.trimIndent(),
            )

        return discovered.map { row ->
            SupabaseCatalogEntry(
                schema = row.string("schema_name") ?: error("schema_name was missing"),
                name = row.string("object_name") ?: error("object_name was missing"),
                kind = row.string("object_kind") ?: error("object_kind was missing"),
            )
        }
    }

    private suspend fun countRows(target: CatalogTarget): SupabaseTableCount? {
        val count =
            database.string(
                query = "SELECT COUNT(*)::text AS row_count FROM ${target.schema}.${target.name}",
                columnName = "row_count",
            )?.toIntOrNull() ?: return null

        return SupabaseTableCount(
            schema = target.schema,
            name = target.name,
            count = count,
        )
    }
}
