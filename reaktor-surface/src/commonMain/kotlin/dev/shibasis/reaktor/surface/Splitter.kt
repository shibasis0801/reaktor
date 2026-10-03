package dev.shibasis.reaktor.surface

fun fitSize(size: Float, min: Float, max: Float): Float = size.coerceIn(min, max.coerceAtLeast(min))

data class SplitterProperties(
    val size: Float,
    val min: Float,
    val max: Float,
    val initial: Float,
    val collapsible: Boolean = false,
    val reversed: Boolean = false,
    val axis: Axis = Axis.Horizontal,
    val rightToLeft: Boolean = false,
)

data class SplitterState(val restore: Float? = null) {
    val collapsed: Boolean get() = restore != null
}

sealed interface SplitterInput {
    data class Drag(val delta: Float) : SplitterInput
    data class Stroke(val stroke: KeyStroke) : SplitterInput
    data object Reset : SplitterInput
}

data class SizeChange(val size: Float, val collapsed: Boolean)

typealias SplitterBehavior = BehaviorKernel<SplitterProperties, SplitterState, SplitterInput, SizeChange>

data class SplitterKernel(val step: Float = 16f) : SplitterBehavior {
    override fun initial(properties: SplitterProperties) = SplitterState()

    override fun reduce(properties: SplitterProperties, state: SplitterState, input: SplitterInput): Reduction<SplitterState, SizeChange> =
        when (input) {
            is SplitterInput.Drag -> move(properties, state, input.delta)
            SplitterInput.Reset -> resize(properties, state, properties.initial)
            is SplitterInput.Stroke -> when (val gesture = gesture(properties, input.stroke)) {
                null -> Reduction(state)
                is Gesture.Step -> move(properties, state, gesture.delta)
                Gesture.Smallest -> resize(properties, state, properties.min)
                Gesture.Largest -> resize(properties, state, properties.max)
                Gesture.Collapse -> collapse(properties, state)
            }
        }

    fun handles(properties: SplitterProperties, stroke: KeyStroke): Boolean = gesture(properties, stroke) != null

    private fun move(properties: SplitterProperties, state: SplitterState, delta: Float): Reduction<SplitterState, SizeChange> {
        val mirrored = properties.axis != Axis.Vertical && properties.rightToLeft
        val growth = if (mirrored != properties.reversed) -delta else delta
        return resize(properties, state, (if (state.collapsed) 0f else properties.size) + growth)
    }

    private fun resize(properties: SplitterProperties, state: SplitterState, size: Float): Reduction<SplitterState, SizeChange> {
        val fitted = fitSize(size, properties.min, properties.max)
        val unchanged = !state.collapsed && fitted == properties.size
        return Reduction(SplitterState(), if (unchanged) emptyList() else listOf(SizeChange(fitted, collapsed = false)))
    }

    private fun collapse(properties: SplitterProperties, state: SplitterState): Reduction<SplitterState, SizeChange> {
        val restore = state.restore ?: return Reduction(SplitterState(restore = properties.size), listOf(SizeChange(0f, collapsed = true)))
        return Reduction(SplitterState(), listOf(SizeChange(fitSize(restore, properties.min, properties.max), collapsed = false)))
    }

    private fun gesture(properties: SplitterProperties, stroke: KeyStroke): Gesture? {
        if (stroke.meta || stroke.control || stroke.alt || stroke.shift) return null
        val horizontal = properties.axis != Axis.Vertical
        return when (stroke.key) {
            KeyName.Left -> if (horizontal) Gesture.Step(-step) else null
            KeyName.Right -> if (horizontal) Gesture.Step(step) else null
            KeyName.Up -> if (horizontal) null else Gesture.Step(-step)
            KeyName.Down -> if (horizontal) null else Gesture.Step(step)
            KeyName.Home -> Gesture.Smallest
            KeyName.End -> Gesture.Largest
            KeyName.Enter -> if (properties.collapsible) Gesture.Collapse else null
            else -> null
        }
    }

    private sealed interface Gesture {
        data class Step(val delta: Float) : Gesture
        data object Smallest : Gesture
        data object Largest : Gesture
        data object Collapse : Gesture
    }
}
