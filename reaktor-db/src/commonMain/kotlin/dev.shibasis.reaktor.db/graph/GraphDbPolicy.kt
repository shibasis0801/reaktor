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
    private val cypherRegexOptions = setOf(RegexOption.IGNORE_CASE)
    private val clauseBoundary = Regex(
        pattern = """\bOPTIONAL\s+MATCH\b|\bMATCH\b|\bWHERE\b|\bWITH\b|\bRETURN\b|\bCREATE\b|\bMERGE\b|\bDELETE\b|\bDETACH\s+DELETE\b|\bSET\b|\bUNWIND\b|\bCALL\b|\bFOREACH\b|\bORDER\s+BY\b|\bLIMIT\b|\bSKIP\b|\bUNION\b""",
        options = cypherRegexOptions
    )
    private val matchClause = Regex("""\b(?:OPTIONAL\s+MATCH|MATCH)\b""", cypherRegexOptions)
    private val nodePattern = Regex("""\(([^()]*)\)""")
    private val tenantProperty = Regex(
        pattern = """(?:^|[,{])\s*`?tenant_id`?\s*:\s*${Regex.escape(tenantParameterReference)}(?:\s|[,}])""",
        options = cypherRegexOptions
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

private val cypherLiteral = Regex("""'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`[^`]*`|//[^\n]*|/\*[\s\S]*?\*/""")

fun String.withoutCypherLiterals(): String = cypherLiteral.replace(this, " ")

class ReadOnlyGraphQuery {
    private val writeClause = Regex(
        pattern = """\b(?:CREATE|MERGE|SET|DELETE|REMOVE|DROP|FOREACH|CALL|LOAD\s+CSV)\b""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    fun validate(cypher: String) {
        writeClause.find(cypher.withoutCypherLiterals())?.let {
            throw InvalidTenantGraphQuery("Read query contains the write or procedure clause ${it.value.uppercase()}")
        }
    }
}

class TenantScopedWrites(
    private val tenantParameterName: String = TenantParameterName,
) {
    private val tenantParameterReference = "\$$tenantParameterName"
    private val cypherRegexOptions = setOf(RegexOption.IGNORE_CASE)
    private val writeClause = Regex("""\b(?:CREATE|MERGE)\b""", cypherRegexOptions)
    private val nextClause = Regex(
        pattern = """\bOPTIONAL\s+MATCH\b|\bMATCH\b|\bWHERE\b|\bWITH\b|\bRETURN\b|\bCREATE\b|\bMERGE\b|\bDELETE\b|\bSET\b|\bUNWIND\b|\bCALL\b|\bFOREACH\b|\bON\b|\bREMOVE\b""",
        options = cypherRegexOptions
    )
    private val nodePattern = Regex("""\(([^()]*)\)""")
    private val tenantProperty = Regex(
        pattern = """(?:^|[,{])\s*`?$tenantParameterName`?\s*:\s*${Regex.escape(tenantParameterReference)}(?:\s|[,}])""",
        options = cypherRegexOptions
    )
    private val schemaStatement = Regex("""\b(?:CREATE|DROP)\s+(?:INDEX|CONSTRAINT|EDGE\s+INDEX|TEXT\s+INDEX|VECTOR\s+INDEX)\b""", cypherRegexOptions)
    private val procedureCall = Regex("""\bCALL\b""", cypherRegexOptions)
    private val tenantReassignment = Regex(
        pattern = """\.\s*`?$tenantParameterName`?\s*=(?!\s*${Regex.escape(tenantParameterReference)}(?![A-Za-z0-9_]))""",
        options = cypherRegexOptions
    )
    private val tenantRemoval = Regex("""\bREMOVE\b[^;]*\.\s*`?$tenantParameterName`?\b""", cypherRegexOptions)

    fun validate(cypher: String) {
        val text = cypher.withoutCypherLiterals()
        if (schemaStatement.containsMatchIn(text)) {
            throw InvalidTenantGraphQuery("Schema statements are not accepted through tenant writes")
        }
        if (procedureCall.containsMatchIn(text)) {
            throw InvalidTenantGraphQuery("Procedure calls are not accepted through tenant writes")
        }
        if (tenantReassignment.containsMatchIn(text) || tenantRemoval.containsMatchIn(text)) {
            throw InvalidTenantGraphQuery("$tenantParameterName can only be set to $tenantParameterReference")
        }
        writeClause.findAll(text).forEach { clause ->
            val start = clause.range.last + 1
            val end = nextClause.find(text, start)?.range?.first ?: text.length
            nodePattern.findAll(text.substring(start, end)).forEach { node ->
                val body = node.groupValues[1]
                val introducesNode = body.contains(':') || body.contains('{') || body.contains('$')
                if (introducesNode && !tenantProperty.containsMatchIn(body.substringAfter('{', ""))) {
                    throw InvalidTenantGraphQuery(
                        "${clause.value.uppercase()} node pattern must include {$tenantParameterName: $tenantParameterReference}: (${body.trim()})"
                    )
                }
            }
        }
    }

    fun validateParameters(tenantId: String, parameters: Map<String, Any?>) {
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, entry) ->
                    if (key == tenantParameterName && entry != tenantId) {
                        throw InvalidTenantGraphQuery("Parameters cannot carry a different $tenantParameterName")
                    }
                    visit(entry)
                }
                is Iterable<*> -> value.forEach(::visit)
                is Array<*> -> value.forEach(::visit)
            }
        }
        parameters.forEach { (key, value) -> if (key != tenantParameterName) visit(value) }
    }
}
