package dev.shibasis.reaktor.auth.fga

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object FgaJson {
    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun encode(model: AuthorizationModel): String = format.encodeToString(JsonElement.serializer(), element(model)) + "\n"

    fun element(model: AuthorizationModel): JsonObject = buildJsonObject {
        put("schema_version", model.schema)
        putJsonArray("type_definitions") { model.types.forEach { add(type(it)) } }
    }

    fun decode(text: String): Result<AuthorizationModel> = runCatching { decode(Json.parseToJsonElement(text)) }

    fun decode(element: JsonElement): AuthorizationModel {
        val root = element.objectOrNull() ?: fail("An authorization model is a JSON object")
        val model = root["authorization_model"]?.objectOrNull() ?: root
        val schema = model.text("schema_version") ?: "1.1"
        if (schema != "1.1") fail("Only schema 1.1 is supported, not $schema")
        if (model["conditions"]?.objectOrNull()?.isNotEmpty() == true) fail("Conditions are not supported yet")
        val types = (model["type_definitions"] as? JsonArray).orEmpty().map { entry ->
            val definition = entry.objectOrNull() ?: fail("Each type definition is a JSON object")
            val name = definition.text("type") ?: fail("A type definition has no type")
            val metadata = definition["metadata"]?.objectOrNull()?.get("relations")?.objectOrNull()
            TypeDefinition(name, definition["relations"]?.objectOrNull().orEmpty().map { (relation, userset) ->
                val direct = (metadata?.get(relation)?.objectOrNull()?.get("directly_related_user_types") as? JsonArray).orEmpty()
                    .map { reference(it.objectOrNull() ?: fail("$name#$relation lists a user type that is not an object"), "$name#$relation") }
                RelationDefinition(relation, rewrite(userset, direct, "$name#$relation"))
            })
        }
        return AuthorizationModel(types, schema)
    }

    private fun type(type: TypeDefinition): JsonObject = buildJsonObject {
        put("type", type.name)
        putJsonObject("relations") { type.relations.forEach { put(it.name, userset(it.rewrite)) } }
        if (type.relations.isEmpty()) put("metadata", JsonNull)
        else putJsonObject("metadata") {
            putJsonObject("relations") {
                type.relations.forEach { relation ->
                    putJsonObject(relation.name) { putJsonArray("directly_related_user_types") { relation.rewrite.directTypes.forEach { add(reference(it)) } } }
                }
            }
        }
    }

    private fun reference(ref: TypeRef): JsonObject = buildJsonObject {
        put("type", ref.type)
        ref.relation?.let { put("relation", it) }
        if (ref.wildcard) putJsonObject("wildcard") {}
    }

    private fun userset(rewrite: Rewrite): JsonObject = when (rewrite) {
        is Rewrite.Direct -> single("this", JsonObject(emptyMap()))
        is Rewrite.Computed -> single("computedUserset", relation(rewrite.relation))
        is Rewrite.From -> single("tupleToUserset", buildJsonObject {
            put("tupleset", relation(rewrite.tupleset))
            put("computedUserset", relation(rewrite.relation))
        })
        is Rewrite.Union -> single("union", children(rewrite.children))
        is Rewrite.Intersection -> single("intersection", children(rewrite.children))
        is Rewrite.Exclusion -> single("difference", buildJsonObject {
            put("base", userset(rewrite.base))
            put("subtract", userset(rewrite.subtract))
        })
    }

    private fun single(key: String, value: JsonElement) = JsonObject(mapOf(key to value))

    private fun relation(name: String) = JsonObject(mapOf("relation" to JsonPrimitive(name)))

    private fun children(children: List<Rewrite>) = JsonObject(mapOf("child" to JsonArray(children.map(::userset))))

    private fun reference(entry: JsonObject, where: String): TypeRef {
        if (entry.text("condition")?.isNotEmpty() == true) fail("$where uses a condition; conditions are not supported yet")
        val type = entry.text("type") ?: fail("$where lists a user type without a type")
        return TypeRef(type, entry.text("relation")?.takeIf(String::isNotEmpty), wildcard = entry["wildcard"]?.objectOrNull() != null)
    }

    private fun rewrite(element: JsonElement, direct: List<TypeRef>, where: String): Rewrite {
        val userset = element.objectOrNull() ?: fail("$where is not a userset object")
        val (key, value) = userset.entries.singleOrNull() ?: fail("$where needs exactly one of this, computedUserset, tupleToUserset, union, intersection or difference")
        val body = value.objectOrNull() ?: fail("$where has a $key that is not an object")
        return when (key) {
            "this" -> Rewrite.Direct(direct)
            "computedUserset" -> Rewrite.Computed(body.relationName(where))
            "tupleToUserset" -> Rewrite.From(
                body["computedUserset"]?.objectOrNull()?.relationName(where) ?: fail("$where has a tupleToUserset without computedUserset"),
                body["tupleset"]?.objectOrNull()?.relationName(where) ?: fail("$where has a tupleToUserset without tupleset"),
            )
            "union" -> Rewrite.Union(body.children(direct, where))
            "intersection" -> Rewrite.Intersection(body.children(direct, where))
            "difference" -> Rewrite.Exclusion(
                rewrite(body["base"] ?: fail("$where has a difference without base"), direct, where),
                rewrite(body["subtract"] ?: fail("$where has a difference without subtract"), direct, where),
            )
            else -> fail("$where uses $key, which is not a userset")
        }
    }

    private fun JsonObject.children(direct: List<TypeRef>, where: String): List<Rewrite> =
        (this["child"] as? JsonArray)?.map { rewrite(it, direct, where) } ?: fail("$where has an operator without child")

    private fun JsonObject.relationName(where: String): String = text("relation")?.takeIf(String::isNotEmpty) ?: fail("$where names no relation")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
