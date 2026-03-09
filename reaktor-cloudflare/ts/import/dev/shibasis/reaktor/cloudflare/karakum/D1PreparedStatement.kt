// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface D1PreparedStatement {
fun bind(vararg values: Any?): D1PreparedStatement
fun first(columnName: String = definedExternally): js.promise.Promise<Any?>
fun run(): js.promise.Promise<D1Result>
fun all(): js.promise.Promise<D1Result>
fun raw(options: D1RawOptions = definedExternally): js.promise.Promise<js.array.ReadonlyArray<Any?>>
}
