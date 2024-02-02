@file:Suppress("KotlinJniMissingFunction")

package com.myntra.appscore.batcave.types

expect class NoArgNativeFunction {
    operator fun invoke(): Any
}


expect class SingleArgNativeFunction {
    operator fun invoke(data: Any): Any
}
