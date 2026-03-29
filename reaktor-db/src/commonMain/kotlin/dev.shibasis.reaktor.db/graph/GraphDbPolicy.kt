package dev.shibasis.reaktor.db.graph

const val TenantParameterName = "tenant_id"
const val TenantParameterReference = "\$tenant_id"

data class TenantGraphQuery(
    val cypher: String,
    val parameters: Map<String, Any?> = emptyMap()
)

class InvalidTenantGraphQuery(message: String): IllegalArgumentException(message)

fun interface TenantGraphQueryPolicy {
    fun bind(tenantId: String, query: TenantGraphQuery): TenantGraphQuery
}

class MandatoryTenantParameterization(
    private val tenantParameterName: String = TenantParameterName,
): TenantGraphQueryPolicy {
    private val tenantParameterReference = "\$$tenantParameterName"
    private val clauseBoundary = Regex(
        pattern = """(?is)\bOPTIONAL\s+MATCH\b|\bMATCH\b|\bWHERE\b|\bWITH\b|\bRETURN\b|\bCREATE\b|\bMERGE\b|\bDELETE\b|\bDETACH\s+DELETE\b|\bSET\b|\bUNWIND\b|\bCALL\b|\bFOREACH\b|\bORDER\s+BY\b|\bLIMIT\b|\bSKIP\b|\bUNION\b"""
    )
    private val matchClause = Regex("""(?is)\b(?:OPTIONAL\s+MATCH|MATCH)\b""")
    private val nodePattern = Regex("""\(([^()]*)\)""")
    private val tenantProperty = Regex(
        pattern = """(?is)(?:^|[,{])\s*`?tenant_id`?\s*:\s*${Regex.escape(tenantParameterReference)}(?:\s|[,}])"""
    )

    override fun bind(tenantId: String, query: TenantGraphQuery): TenantGraphQuery {
        validate(query.cypher)
        return query.copy(parameters = query.parameters + (tenantParameterName to tenantId))
    }

    fun validate(cypher: String) {
        if (!cypher.contains(tenantParameterReference)) {
            throw InvalidTenantGraphQuery(
                "Cypher must declare $tenantParameterReference so the framework can bind the current tenant"
            )
        }

        extractMatchClauses(cypher).forEach { clause ->
            val patterns = nodePattern.findAll(clause).map { it.groupValues[1] }.toList()
            if (patterns.isEmpty()) {
                throw InvalidTenantGraphQuery("MATCH clause does not contain a node pattern: ${clause.trim()}")
            }
            patterns.forEach { pattern ->
                if (!tenantProperty.containsMatchIn(pattern)) {
                    throw InvalidTenantGraphQuery(
                        "MATCH node pattern must include {tenant_id: $tenantParameterReference}: (${pattern.trim()})"
                    )
                }
            }
        }
    }

    private fun extractMatchClauses(cypher: String): List<String> {
        val matches = matchClause.findAll(cypher).toList()
        if (matches.isEmpty()) return emptyList()

        return matches.map { match ->
            val clauseStart = match.range.last + 1
            val nextBoundary = clauseBoundary.find(cypher, clauseStart)?.range?.first ?: cypher.length
            cypher.substring(clauseStart, nextBoundary)
        }
    }
}
