package dev.shibasis.reaktor.surface

interface ThemeSnapshot {
    val id: String
}

fun interface ComponentRecipe<P : Any, S : Any, V : Any> {
    fun resolve(properties: P, state: S, theme: ThemeSnapshot): V
}
