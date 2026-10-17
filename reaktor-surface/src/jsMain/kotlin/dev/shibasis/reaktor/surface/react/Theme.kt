package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*

private object BareReactTheme : ThemeSnapshot { override val id = "bare" }
/** Browser styles stay in the DOM adapter; recipes still consume the common theme snapshot. */
class ReactAppearanceKey<A : Any>(val name: String, val default: A)
class ReactAppearances(private val entries: Map<ReactAppearanceKey<*>, Any> = emptyMap(),
    private val controls: Map<String, ReactAppearance> = emptyMap()) {
    @Suppress("UNCHECKED_CAST") operator fun <A : Any> get(key: ReactAppearanceKey<A>): A = entries[key] as A? ?: key.default
    fun <A : Any> with(key: ReactAppearanceKey<A>, value: A) = ReactAppearances(entries + (key to value), controls)
    fun with(component: ReactSurface, appearance: ReactAppearance) = ReactAppearances(entries, controls + (component.name to appearance))
    internal fun control(name: String): ReactAppearance? = controls[name]
}
enum class ReactSurface {
    Action, Panel, Field, Form, Dialog, Tabs, Checkbox, Switch, Select, RadioGroup, ToggleGroup,
    Disclosure, Collection, Menu, Tooltip, Splitter, ToastHost, Progress, Separator, Badge, Text, Icon, StateView,
    DataTable, DocumentTabs, Popover, Sheet, Bar, Section, Property, Metric, Finding, ListRow, ActionGroup,
    ContactPlate, MovingFace, Tag, Sparkline, Bars, RangeBar,
}
class ReactAppearanceSlot(val properties: dynamic, val theme: ThemeSnapshot, val content: ReactNode)
fun interface ReactAppearance { fun render(slot: ReactAppearanceSlot): ReactNode }
internal fun surfaceAppearance(name: String, props: dynamic, content: dynamic): dynamic {
    val environment = useSurfaceEnvironment()
    return environment.appearances.control(name)?.render(ReactAppearanceSlot(props, environment.snapshot, content.unsafeCast<ReactNode>())) ?: content
}
data class ReactEnvironment(val snapshot: ThemeSnapshot = BareReactTheme, val appearances: ReactAppearances = ReactAppearances(),
    val keys: KeyConvention = browserKeys(), val reducedMotion: Boolean = false, val rightToLeft: Boolean = false,
    val onCue: (FeedbackCue) -> Unit = {})
private val themeContext = createContext(ReactEnvironment())
external interface SurfaceThemeProps : PropsWithChildren { var environment: ReactEnvironment }
val SurfaceTheme = FC<SurfaceThemeProps> { props ->
    themeContext.Provider { value = props.environment; +props.children }
}
fun useSurfaceEnvironment(): ReactEnvironment = useContext(themeContext)
fun <P : Any, S : Any, V : Any> useSurfaceRecipe(recipe: ComponentRecipe<P, S, V>, properties: P, state: S): V =
    recipe.resolve(properties, state, useSurfaceEnvironment().snapshot)
