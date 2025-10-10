package dev.shibasis.reaktor.navigation.screen

import dev.shibasis.reaktor.navigation.Event
import dev.shibasis.reaktor.navigation.InputSignal
import dev.shibasis.reaktor.navigation.Renderer
import dev.shibasis.reaktor.navigation.Screen
import react.StateInstance
import react.StateSetter
import react.useEffect
import react.useMemo
import react.useRef
import react.useState

class ReactRenderer: Renderer()

@JsExport
abstract class ReactScreen<I: InputSignal, E: Event>(
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
            screen.state.collect {
                if (it != indirectStateToAvoidClosure.current)
                    setState(it)
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


class ReactListDetailContainer(
    val listScreen: ReactScreen<InputSignal, Event>,
    val detailScreen: ReactScreen<InputSignal, Event>
)


