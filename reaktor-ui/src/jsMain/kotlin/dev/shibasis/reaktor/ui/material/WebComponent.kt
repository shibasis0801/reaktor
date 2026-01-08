package dev.shibasis.reaktor.ui.material


import react.useEffect
import react.useState
import web.html.HTMLElement
import kotlin.js.Promise
import kotlin.js.JsExport

@JsExport
enum class PromiseState { Initial, Pending, Resolved, Rejected }

@JsExport
data class PromiseResult<T>(
    val state: PromiseState,
    val data: T? = null,
    val error: Throwable? = null
)

@JsExport
fun<T> usePromise(
    vararg dependencies: Any?,
    promiseFactory: () -> Promise<T>?,
): PromiseResult<T> {
    val (data, setData) = useState(PromiseResult<T>(PromiseState.Initial))

    useEffect(dependencies) {
        val promise = promiseFactory() ?: return@useEffect

        setData(PromiseResult(PromiseState.Pending))

        promise.then { value ->
            setData(PromiseResult(PromiseState.Resolved, value))
        }.catch { error ->
            setData(PromiseResult(PromiseState.Rejected, error = error))
        }
    }

    return data
}
