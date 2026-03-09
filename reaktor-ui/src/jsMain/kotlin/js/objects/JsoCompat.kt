@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package js.objects

/**
 * Compatibility helper for projects that still reference js.objects.jso.
 * Kotlin JS wrappers no longer expose this symbol in all versions.
 */
inline fun <T : Any> jso(builder: T.() -> Unit): T = (js("({})") as T).apply(builder)
