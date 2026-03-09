// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface D1Database {
fun prepare(query: String): D1PreparedStatement
fun batch(statements: js.array.ReadonlyArray<D1PreparedStatement>): js.promise.Promise<js.array.ReadonlyArray<D1Result>>
fun exec(query: String): js.promise.Promise<D1ExecResult>
}
