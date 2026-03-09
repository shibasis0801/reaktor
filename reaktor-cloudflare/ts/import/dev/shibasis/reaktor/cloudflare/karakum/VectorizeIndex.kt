// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface VectorizeIndex {
fun describe(): js.promise.Promise<Any?>
fun insert(vectors: js.array.ReadonlyArray<VectorizeVector>): js.promise.Promise<VectorizeMutationResult>
fun upsert(vectors: js.array.ReadonlyArray<VectorizeVector>): js.promise.Promise<VectorizeMutationResult>
fun query(vector: js.array.ReadonlyArray<Double>, options: VectorizeQueryOptions = definedExternally): js.promise.Promise<VectorizeMatches>
fun getByIds(ids: js.array.ReadonlyArray<String>): js.promise.Promise<js.array.ReadonlyArray<VectorizeVector>>
fun deleteByIds(ids: js.array.ReadonlyArray<String>): js.promise.Promise<VectorizeMutationResult>
}
