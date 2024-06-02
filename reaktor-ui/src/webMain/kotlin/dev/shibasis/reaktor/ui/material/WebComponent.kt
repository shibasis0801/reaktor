package dev.shibasis.reaktor.ui.material

import react.Dependencies
import react.useEffect
import react.useState
import web.html.HTMLElement
import kotlin.js.Promise

enum class PromiseState { Initial, Pending, Resolved, Rejected }
data class PromiseResult<T>(
    val state: PromiseState,
    val data: T? = null,
    val error: Throwable? = null
)
fun<T> usePromise(
    promiseFactory: () -> Promise<T>?,
    dependencies: Dependencies
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
