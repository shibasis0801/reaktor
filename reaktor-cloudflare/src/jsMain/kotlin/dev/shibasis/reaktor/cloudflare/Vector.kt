package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement

class VectorRecord(
    val id: String,
    val values: List<Double>,
    val namespace: String? = null,
    val metadata: JsonElement? = null,
)

class VectorMatch(
    val id: String,
    val score: Double,
    val values: List<Double>? = null,
    val metadata: JsonElement? = null,
)

class VectorQuery(
    val topK: Int? = null,
    val namespace: String? = null,
    val returnValues: Boolean? = null,
    val returnMetadata: Boolean? = null,
    val filter: JsonElement? = null,
)

class VectorMatches(
    val matches: List<VectorMatch>,
    val count: Int? = null,
)

class VectorMutation(
    val ids: List<String> = emptyList(),
    val count: Int? = null,
)

class VectorIndex internal constructor(
    private val raw: RawVectorizeIndex,
) {
    suspend fun describe(): JsonElement = dynamicToJsonElement(raw.describe().await())

    suspend fun insert(vectors: List<VectorRecord>): VectorMutation =
        raw.insert(vectors.map(VectorRecord::toRaw).toTypedArray()).await().toModel()

    suspend fun upsert(vectors: List<VectorRecord>): VectorMutation =
        raw.upsert(vectors.map(VectorRecord::toRaw).toTypedArray()).await().toModel()

    suspend fun query(
        vector: List<Double>,
        options: VectorQuery = VectorQuery(),
    ): VectorMatches =
        raw.query(
            vector.map(Double::toNumber).toTypedArray(),
            options.toRaw(),
        ).await().toModel()

    suspend fun get(ids: Collection<String>): List<VectorRecord> =
        raw.getByIds(ids.toTypedArray()).await().map(RawVectorizeVector::toModel)

    suspend fun delete(ids: Collection<String>): VectorMutation =
        raw.deleteByIds(ids.toTypedArray()).await().toModel()
}

private fun VectorRecord.toRaw(): RawVectorizeVector {
    val vector = js("({})").unsafeCast<RawVectorizeVector>()
    vector.id = id
    vector.values = values.map(Double::toNumber).toTypedArray()
    vector.namespace = namespace
    vector.metadata = metadata?.toDynamic()
    return vector
}

private fun VectorQuery.toRaw(): RawVectorizeQueryOptions {
    val options = js("({})").unsafeCast<RawVectorizeQueryOptions>()
    options.topK = topK
    options.namespace = namespace
    options.returnValues = returnValues
    options.returnMetadata = returnMetadata
    options.filter = filter?.toDynamic()
    return options
}

private fun RawVectorizeVector.toModel(): VectorRecord =
    VectorRecord(
        id = id,
        values = values.map(Number::toDouble),
        namespace = namespace,
        metadata = metadata?.let(::dynamicToJsonElement),
    )

private fun RawVectorizeMatch.toModel(): VectorMatch =
    VectorMatch(
        id = id,
        score = score.toDouble(),
        values = values?.map(Number::toDouble),
        metadata = metadata?.let(::dynamicToJsonElement),
    )

private fun RawVectorizeMatches.toModel(): VectorMatches =
    VectorMatches(
        matches = matches.map(RawVectorizeMatch::toModel),
        count = count?.toInt(),
    )

private fun RawVectorizeMutationResult.toModel(): VectorMutation =
    VectorMutation(
        ids = ids?.toList().orEmpty(),
        count = count?.toInt(),
    )

private fun Double.toNumber(): Number = this
