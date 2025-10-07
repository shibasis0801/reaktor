package dev.shibasis.reaktor.navigation.screen

import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.navigation.Event
import dev.shibasis.reaktor.navigation.Input
import dev.shibasis.reaktor.navigation.Renderer
import dev.shibasis.reaktor.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import react.StateInstance
import react.StateSetter
import react.useEffect
import react.useMemo
import react.useRef
import react.useState

class ReactRenderer: Renderer()

@JsExport
abstract class ReactScreen<I: Input, E: Event>(
    input: I
): Screen<I, E>(input) {
    fun setScreenState(input: I) {
        state.value = input
    }

    fun useScreenState(): StateInstance<I> {
        val screen = this

        val (state, setState) = useState(screen.state.value)
        val indirectStateToAvoidClosure = useRef(state)
        indirectStateToAvoidClosure.current = state

        // stateflow to react
        useEffect(screen) {
            val job = Dispatch.Main.launch {
                screen.state.collect {
                    if (it != indirectStateToAvoidClosure.current)
                        setState(it)
                }
            }

            cleanup {
                job.cancel()
            }
        }

        // react to stateflow
        val stateSetter = useMemo(screen) {
            { valueOrTransform: dynamic ->
                if (js("typeof valueOrTransform === 'function'")) {
                    val transform = valueOrTransform.unsafeCast<(I) -> I>()
                    setScreenState(transform(screen.state.value))
                } else {
                    val newValue = valueOrTransform.unsafeCast<I>()
                    setScreenState(newValue)
                }
            }.unsafeCast<StateSetter<I>>()
        }

        return StateInstance(state, stateSetter)
    }

    abstract fun render()
}


