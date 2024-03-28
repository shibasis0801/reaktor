package dev.shibasis.flatinvoker.core

fun patnaik1(string: String?): Int {
    return string!!.length
}

fun patnaik2(string: String): Int {
    return string.length
}

val shibasis1: String
    get() = "Shibasis1"

val shibasis2 = "Shibasis2"