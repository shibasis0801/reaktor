package dev.shibasis.reaktor.surface

interface ThemeSnapshot {
    val id: String
}

class ThemeMismatch(val expected: String, val actual: String) :
    IllegalStateException("A $expected appearance was drawn under the theme '$actual'")

fun interface ComponentRecipe<P : Any, S : Any, V : Any> {
    fun resolve(properties: P, state: S, theme: ThemeSnapshot): V
}
