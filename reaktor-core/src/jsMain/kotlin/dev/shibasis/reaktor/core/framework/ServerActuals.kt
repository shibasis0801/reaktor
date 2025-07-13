package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

// noop
actual class WeakRef<T> actual constructor(referred: T) {
    private val ref = referred
    actual fun get(): T? {
        return ref
    }
}

actual class AtomicInt actual constructor(value: Int){
    private var data = value
    actual fun getAndIncrement(): Int {
        val result = data
        data += 1
        return result
    }
}

@JsExport
sealed class JsResult<T>(val status: String)
@JsExport
data class JsSuccessResult<T>(val value: T): JsResult<T>("success")
@JsExport
data class JsFailureResult<T>(val error: Throwable): JsResult<T>("failure")


fun<T> Result<T>.toJsResult(): JsResult<T> = fold(
    onSuccess = { JsSuccessResult(it) },
    onFailure = { JsFailureResult(it) }
)


fun<T> suspendPromise(block: suspend () -> Result<T>) = GlobalScope.promise {
    block().toJsResult()
}