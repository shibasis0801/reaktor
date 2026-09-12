package dev.shibasis.reaktor.tooling.infra

internal object PostgresInspectorCheck {
    val sql = """
        WITH role_risk AS (
            SELECT
              r.rolname,
              r.rolsuper,
              r.rolcreaterole,
              r.rolcreatedb,
              r.rolreplication,
              r.rolbypassrls,
              EXISTS (
                SELECT 1 FROM pg_roles inherited
                WHERE pg_has_role(r.oid, inherited.oid, 'MEMBER')
                  AND inherited.oid <> r.oid
                  AND (
                    inherited.rolsuper OR inherited.rolcreaterole OR inherited.rolcreatedb
                    OR inherited.rolreplication OR inherited.rolbypassrls
                    OR inherited.rolname IN ('pg_read_server_files', 'pg_write_server_files', 'pg_execute_server_program')
                  )
              ) AS elevated_membership,
              EXISTS (
                SELECT 1 FROM pg_class relation
                JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                WHERE relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                  AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND has_table_privilege(r.oid, relation.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
              ) AS table_write,
              EXISTS (
                SELECT 1 FROM pg_namespace namespace
                WHERE namespace.nspname NOT LIKE 'pg_temp_%'
                  AND namespace.nspname NOT LIKE 'pg_toast_temp_%'
                  AND has_schema_privilege(r.oid, namespace.oid, 'CREATE')
              ) AS schema_create,
              EXISTS (
                SELECT 1 FROM pg_proc function
                JOIN pg_namespace namespace ON namespace.oid = function.pronamespace
                WHERE has_function_privilege(r.oid, function.oid, 'EXECUTE')
                  AND (
                    namespace.nspname NOT IN ('pg_catalog', 'information_schema')
                    OR function.proname IN (
                      'lo_export', 'lo_import', 'nextval', 'setval', 'set_config',
                      'pg_cancel_backend', 'pg_reload_conf', 'pg_terminate_backend',
                      'pg_create_logical_replication_slot', 'pg_drop_replication_slot',
                      'pg_advisory_lock', 'pg_advisory_lock_shared'
                    )
                    OR function.proname LIKE 'dblink%'
                  )
              ) AS callable_risk
            FROM pg_roles r
            WHERE r.rolname = current_user
          )
          SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls,
                 elevated_membership OR table_write OR schema_create, callable_risk
          FROM role_risk;
    """.trimIndent()
}
