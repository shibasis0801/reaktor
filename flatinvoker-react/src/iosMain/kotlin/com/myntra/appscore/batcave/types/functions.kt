package com.myntra.appscore.batcave.types


// Figure out how to make this zero-cost
actual class NoArgNativeFunction(val nativeInvoker: (() -> Any)) {
    actual inline operator fun invoke() = nativeInvoker()
}

actual class SingleArgNativeFunction(val nativeInvoker: ((Any) -> Any)) {
    actual operator fun invoke(data: Any) = nativeInvoker(data)
}
